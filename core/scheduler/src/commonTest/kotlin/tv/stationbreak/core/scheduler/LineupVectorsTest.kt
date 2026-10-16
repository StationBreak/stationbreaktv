// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.vectors.LineupVectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/lineup.json` against `buildDay` and `Day.whatsOn`: whole days from an independent
 * reference (Python, written from docs/rules-format.md only). Every block of every day is checked, every query, and
 * every time outside the day must be an error. A case is never changed to suit the code; a disagreement is reported
 * with the case's name.
 */
class LineupVectorsTest {
    private val cases = VectorJson.parse(LineupVectorFile.text).array("cases").objects()

    private fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.settings(): SchedulerSettings {
        val timing = obj("timing")
        val day = timing.obj("broadcastDay")
        val (hours, minutes) = day.string("start").split(":").map { it.toInt() }
        return SchedulerSettings(
            orderingMode = OrderingMode.entries.single { it.wireName == obj("ordering").string("mode") },
            seed = Seed(string("seed").toULong()),
            blockLength = timing.getValue("blockLength").jsonPrimitive.int,
            alignToGrid =
                timing
                    .getValue("alignToGrid")
                    .jsonPrimitive.content
                    .toBoolean(),
            gridMinutes = timing.getValue("gridMinutes").jsonPrimitive.int,
            epochHours = timing.getValue("epochHours").jsonPrimitive.int,
            utcOffsetMinutes = day.getValue("utcOffsetMinutes").jsonPrimitive.int,
            startMinuteOfDay = hours * 60 + minutes,
            graceHours = timing.getValue("graceHours").jsonPrimitive.int,
            minRuntimeMs = timing.getValue("minRuntimeSeconds").jsonPrimitive.int * 1000L,
        )
    }

    private fun JsonObject.item(): ScheduleItem =
        ScheduleItem(
            id = CanonicalId.parse(string("id")),
            kind = if (string("kind") == "movie") ScheduleItemKind.Movie else ScheduleItemKind.Episode,
            runtimeMs = string("runtimeMs").toLong(),
            createdAtMs = string("createdAtMs").toLong(),
            deletedAtMs = optional("deletedAtMs")?.toLong(),
            premiereDate = optional("premiereDate"),
            productionYear = this["productionYear"]?.jsonPrimitive?.intOrNull,
            seriesId = optional("seriesId")?.let { CanonicalId.parse(it) },
            seasonNumber = this["season"]?.jsonPrimitive?.intOrNull,
            episodeNumber = this["episode"]?.jsonPrimitive?.intOrNull,
        )

    private fun JsonObject.block(): ScheduleBlock {
        val content =
            if (this["slate"]?.jsonPrimitive?.content == "true") {
                ScheduleContent.Slate
            } else {
                ScheduleContent.Program(CanonicalId.parse(string("itemId")))
            }
        return ScheduleBlock(string("startMs").toLong(), string("endMs").toLong(), content)
    }

    private fun failuresOf(case: JsonObject): List<String> {
        val name = case.string("name")
        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (expected != actual) failures.add("[$name] $what: reference $expected, Kotlin $actual")
        }
        val settings = case.obj("settings").settings()
        val items = case.array("items").objects().map { it.item() }
        val day = buildDay(settings, items, case.string("n").toLong())
        val expectedBlocks = case.array("blocks").objects().map { it.block() }
        expect("blocks", expectedBlocks, day.blocks)
        for (query in case.array("queries").objects()) {
            val t = query.string("t").toLong()
            val k = query.getValue("k").jsonPrimitive.int
            val label = "whatsOn($t, $k)"
            val at = day.whatsOn(t, k)
            expect("$label block", expectedBlocks[query.getValue("blockIndex").jsonPrimitive.int], at.block)
            expect("$label offset", query.optional("offsetMs")?.toLong(), at.offsetMs)
            val nextIndexes = query.array("nextIndexes").map { it.jsonPrimitive.int }
            expect("$label next", nextIndexes.map { expectedBlocks[it] }, at.next)
        }
        val errorTimes = (case["errorTimes"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toLong() }
        for (t in errorTimes) {
            val thrown = runCatching { day.whatsOn(t, 0) }.exceptionOrNull()
            expect("whatsOn($t) outside the day throws", true, thrown is IllegalArgumentException)
        }
        return failures
    }

    @Test
    fun theFileHasAtLeastFiftyCases() {
        assertTrue(cases.size >= 50, "${cases.size} cases")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        val failures = cases.flatMap { failuresOf(it) }
        val report = "${failures.size} disagreements with the reference:\n" + failures.joinToString("\n")
        assertTrue(failures.isEmpty(), report)
    }

    @Test
    fun everyDayTilesItsEpochWithNothingCrossingTheBoundary() {
        for (case in cases) {
            val blocks = case.array("blocks").objects().map { it.block() }
            val settings = case.obj("settings").settings()
            val n = case.string("n").toLong()
            val start = Epochs.epochStart(settings, n)
            assertEquals(start, blocks.first().startMs, case.string("name"))
            assertEquals(start + settings.epochMs, blocks.last().endMs, case.string("name"))
            for ((a, b) in blocks.zipWithNext()) assertEquals(a.endMs, b.startMs, case.string("name"))
        }
    }

    @Test
    fun aTimeOutsideTheDayThrows() {
        val day = buildDay(testSettings(), emptyList(), 0L)
        assertFailsWith<IllegalArgumentException> { day.whatsOn(day.endMs, 0) }
    }
}
