// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.MS_MINUTE
import tv.stationbreak.core.scheduler.buildDay
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the day cases of `lineup.json` and `lineup-v2.json` (and the items of the key-order files) and checks a case
 * against `buildDay` and `Day.whatsOn`. A case is never changed to suit the code; a disagreement is reported with the
 * case's name.
 */
internal object DayCases {
    private const val MS_SECOND = 1000L

    fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The settings of a case. The v2 fields (`caps`, `runtimeFilter`) are absent from a version 1 case. */
    fun JsonObject.settings(): SchedulerSettings {
        val timing = obj("timing")
        val day = timing.obj("broadcastDay")
        val (hours, minutes) = day.string("start").split(":").map { it.toInt() }
        return SchedulerSettings(
            orderingMode = OrderingMode.entries.single { it.wireName == obj("ordering").string("mode") },
            seed = Seed(string("seed").toULong()),
            blockLength = timing.getValue("blockLength").jsonPrimitive.int,
            // A case may omit alignToGrid: the version 1 default is true.
            alignToGrid = timing["alignToGrid"]?.jsonPrimitive?.content?.toBoolean() ?: true,
            gridMinutes = timing.getValue("gridMinutes").jsonPrimitive.int,
            epochHours = timing.getValue("epochHours").jsonPrimitive.int,
            utcOffsetMinutes = day.getValue("utcOffsetMinutes").jsonPrimitive.int,
            startMinuteOfDay = hours * 60 + minutes,
            graceHours = timing.getValue("graceHours").jsonPrimitive.int,
            minRuntimeMs = timing.getValue("minRuntimeSeconds").jsonPrimitive.int * MS_SECOND,
            caps = (this["caps"] as? JsonObject)?.caps(),
            runtimeFilter = (this["runtimeFilter"] as? JsonObject)?.runtimeFilter(),
        )
    }

    fun JsonObject.caps(): Caps = Caps(getValue("series").jsonPrimitive.int, getValue("film").jsonPrimitive.int)

    private fun JsonObject.runtimeFilter(): RuntimeFilter =
        RuntimeFilter(
            minMs = this["minMinutes"]?.jsonPrimitive?.intOrNull?.let { it * MS_MINUTE },
            maxMs = this["maxMinutes"]?.jsonPrimitive?.intOrNull?.let { it * MS_MINUTE },
        )

    /** The `films` object (film ID to collection ID); absent means none. */
    fun JsonObject?.films(): FilmCollections =
        FilmCollections.of(
            this?.entries.orEmpty().associate { (film, collection) ->
                canonical(film) to canonical(collection.jsonPrimitive.content)
            },
        )

    // The strict form: a vector ID that is not canonical must be rejected, not repaired.
    fun canonical(text: String): CanonicalId =
        CanonicalId.parseCanonicalOrNull(text) ?: throw IllegalArgumentException("not a canonical ID: $text")

    fun JsonObject.item(): ScheduleItem =
        ScheduleItem(
            id = canonical(string("id")),
            kind = kindOf(string("kind")),
            runtimeMs = string("runtimeMs").toLong(),
            createdAtMs = string("createdAtMs").toLong(),
            deletedAtMs = optional("deletedAtMs")?.toLong(),
            premiereDate = optional("premiereDate"),
            productionYear = this["productionYear"]?.jsonPrimitive?.intOrNull,
            seriesId = optional("seriesId")?.let { canonical(it) },
            seasonNumber = this["season"]?.jsonPrimitive?.intOrNull,
            episodeNumber = this["episode"]?.jsonPrimitive?.intOrNull,
        )

    /** An item of a key-order file: only the fields an order reads, and a runtime of 30 minutes. */
    fun JsonObject.keyOrderItem(): ScheduleItem =
        ScheduleItem(
            id = CanonicalId.parse(string("id")),
            kind = kindOf(string("kind")),
            runtimeMs = 30 * MS_MINUTE,
            createdAtMs = 0L,
            deletedAtMs = null,
            premiereDate = optional("premiere"),
            productionYear = this["year"]?.jsonPrimitive?.intOrNull,
            seriesId = optional("seriesId")?.let { CanonicalId.parse(it) },
            seasonNumber = this["season"]?.jsonPrimitive?.intOrNull,
            episodeNumber = this["episode"]?.jsonPrimitive?.intOrNull,
        )

    private fun kindOf(kind: String): ScheduleItemKind =
        when (kind) {
            "movie" -> ScheduleItemKind.Movie
            "episode" -> ScheduleItemKind.Episode
            else -> throw IllegalArgumentException("unknown kind $kind")
        }

    fun JsonObject.block(): ScheduleBlock {
        val content =
            if (this["slate"]?.jsonPrimitive?.content == "true") {
                ScheduleContent.Slate
            } else {
                ScheduleContent.Program(CanonicalId.parse(string("itemId")))
            }
        return ScheduleBlock(string("startMs").toLong(), string("endMs").toLong(), content)
    }

    /** Every way [case] disagrees with the reference: its blocks, every query, and every error. */
    fun failuresOf(case: JsonObject): List<String> {
        val name = case.string("name")
        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (expected != actual) failures.add("[$name] $what: reference $expected, Kotlin $actual")
        }
        if (case["inputError"] != null) {
            val thrown =
                runCatching {
                    val items = case.array("items").objects().map { it.item() }
                    val settings = case.obj("settings")
                    buildDay(settings.settings(), items, case.string("n").toLong(), settings["films"].filmsOrNone())
                }
            if (thrown.isSuccess) failures.add("[$name] input error: building should be rejected, but it built a day")
            return failures
        }
        val settings = case.obj("settings")
        val items = case.array("items").objects().map { it.item() }
        val day = buildDay(settings.settings(), items, case.string("n").toLong(), settings["films"].filmsOrNone())
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
        val errorKs = (case["errorKs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.int }
        for (k in errorKs) {
            val thrown = runCatching { day.whatsOn(day.startMs, k) }.exceptionOrNull()
            expect("whatsOn(k = $k) throws", true, thrown is IllegalArgumentException)
        }
        return failures
    }

    private fun JsonElement?.filmsOrNone(): FilmCollections = (this as? JsonObject).films()

    /** Every day of [cases] starts at its epoch's start, ends at the epoch's end, and has no gap or overlap. */
    fun assertEveryDayTilesItsEpoch(cases: List<JsonObject>) {
        for (case in cases.filter { it["inputError"] == null }) {
            val blocks = case.array("blocks").objects().map { it.block() }
            val settings = case.obj("settings").settings()
            val n = case.string("n").toLong()
            val start = Epochs.epochStart(settings, n)
            assertEquals(start, blocks.first().startMs, case.string("name"))
            assertEquals(start + settings.epochMs, blocks.last().endMs, case.string("name"))
            for ((a, b) in blocks.zipWithNext()) assertEquals(a.endMs, b.startMs, case.string("name"))
        }
    }

    /** Asserts that every case agrees, reporting all disagreements at once. */
    fun assertEveryCaseAgrees(cases: List<JsonObject>) {
        val failures = cases.flatMap { failuresOf(it) }
        val report = "${failures.size} disagreements with the reference:\n" + failures.joinToString("\n")
        assertTrue(failures.isEmpty(), report)
    }
}
