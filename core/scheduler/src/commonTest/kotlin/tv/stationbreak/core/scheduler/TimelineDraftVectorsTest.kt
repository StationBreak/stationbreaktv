// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.vectors.TimelineDraftVectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import tv.stationbreak.core.scheduler.vectors.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 26 DRAFT timelines from the independent reference (a Python implementation written from the spec, not from this
 * code) against `buildDay` and `Day.whatsOn`. They are not published: task 4.0 publishes `lineup.json`. A case is
 * never changed to suit the code; a disagreement is reported with the case's name.
 */
class TimelineDraftVectorsTest {
    private val vectors = VectorJson.parse(TimelineDraftVectorFile.text).array("vectors").objects()

    private fun JsonObject.settings(): SchedulerSettings {
        val (hours, minutes) = string("start").split(":").map { it.toInt() }
        return SchedulerSettings(
            orderingMode =
                when (string("mode")) {
                    "shuffle" -> OrderingMode.Shuffle
                    "chronological" -> OrderingMode.Chronological
                    "randomInOrder" -> OrderingMode.RandomInOrder
                    else -> error("unknown mode ${string("mode")}")
                },
            seed = Seed(u64(getValue("seed"))),
            blockLength = getValue("blockLength").jsonPrimitive.content.toInt(),
            alignToGrid = getValue("alignToGrid").jsonPrimitive.content.toBoolean(),
            gridMinutes = getValue("gridMinutes").jsonPrimitive.content.toInt(),
            epochHours = getValue("epochHours").jsonPrimitive.content.toInt(),
            utcOffsetMinutes = getValue("utcOffsetMinutes").jsonPrimitive.content.toInt(),
            startMinuteOfDay = hours * 60 + minutes,
            graceHours = getValue("graceHours").jsonPrimitive.content.toInt(),
        )
    }

    private fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.item(): ScheduleItem =
        ScheduleItem(
            id = CanonicalId.parse(string("id")),
            kind = if (string("kind") == "movie") ScheduleItemKind.Movie else ScheduleItemKind.Episode,
            runtimeMs = string("runtimeMs").toLong(),
            createdAtMs = string("createdAtMs").toLong(),
            deletedAtMs = optional("deletedAtMs")?.toLong(),
            premiereDate = optional("premiere"),
            productionYear = null,
            seriesId = optional("seriesId")?.let { CanonicalId.parse(it) },
            seasonNumber = this["season"]?.jsonPrimitive?.intOrNull,
            episodeNumber = this["episode"]?.jsonPrimitive?.intOrNull,
        )

    private fun JsonObject.block(): ScheduleBlock {
        val content =
            if (optional("itemId") != null) {
                ScheduleContent.Program(CanonicalId.parse(string("itemId")), string("mediaStartOffsetMs").toLong())
            } else {
                ScheduleContent.Slate
            }
        return ScheduleBlock(string("start").toLong(), string("end").toLong(), content)
    }

    private fun failuresOf(vector: JsonObject): List<String> {
        val name = vector.string("name")
        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (expected != actual) failures.add("[$name] $what: reference $expected, Kotlin $actual")
        }
        val settings = vector.obj("settings").settings()
        val items = vector.array("items").objects().map { it.item() }
        val day = buildDay(settings, items, vector.string("n").toLong())
        val dayJson = vector.obj("day")
        expect("epochStart", dayJson.string("epochStart").toLong(), day.startMs)
        expect("epochEnd", dayJson.string("epochEnd").toLong(), day.endMs)
        val expectedBlocks = dayJson.array("blocks").objects().map { it.block() }
        expect("blocks", expectedBlocks, day.blocks)
        // An empty cycle (empty pool, or every run left out) has neither field in the reference's output.
        expect("cycleRuns", dayJson["cycleRuns"]?.jsonPrimitive?.intOrNull ?: 0, day.cycleRunCount)
        val startRun = dayJson["startRunIndexInCycle"]?.jsonPrimitive?.intOrNull
        if (startRun != null) expect("startRunIndex", startRun, day.startRunIndex)
        for (query in vector.array("queries").objects()) {
            val t = query.string("t").toLong()
            val k =
                query
                    .getValue("k")
                    .jsonPrimitive.content
                    .toInt()
            val at = day.whatsOn(t, k)
            val index =
                query
                    .getValue("blockIndex")
                    .jsonPrimitive.content
                    .toInt()
            val label = "whatsOn($t, $k)"
            expect("$label block", expectedBlocks[index], at.block)
            expect("$label offset", query.optional("offsetMs")?.toLong(), at.offsetMs)
            val nextIndexes = query.array("nextBlockIndexes").map { it.jsonPrimitive.content.toInt() }
            expect("$label next", nextIndexes.map { expectedBlocks[it] }, at.next)
        }
        return failures
    }

    @Test
    fun theDraftCasesAreAllThere() {
        assertEquals(26, vectors.size)
    }

    @Test
    fun everyDraftCaseAgreesWithTheReference() {
        val failures = vectors.flatMap { failuresOf(it) }
        val report = "${failures.size} disagreements with the reference:\n" + failures.joinToString("\n")
        assertTrue(failures.isEmpty(), report)
    }
}
