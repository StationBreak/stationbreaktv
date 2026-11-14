// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

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
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.CuratedTuning
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleKeeps
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.MS_MINUTE
import tv.stationbreak.core.scheduler.buildDay
import tv.stationbreak.core.scheduler.noKeeps
import tv.stationbreak.core.scheduler.noWeights
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the day cases of `lineup.json`, `lineup-v2.json` and `lineup-v3.json` (and the items of the key-order files)
 * and checks a case against `buildDay` and `Day.whatsOn`. A case is never changed to suit the code; a disagreement is
 * reported with the case's name.
 */
internal object DayCases {
    private const val MS_SECOND = 1000L

    fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The settings of a case. The v2 fields (`caps`, `runtimeFilter`) are absent from a version 1 case. */
    fun JsonObject.settings(): SchedulerSettings {
        val timing = obj("timing")
        val day = timing.obj("broadcastDay")
        val (hours, minutes) = day.string("start").split(":").map { it.toInt() }
        val mode = orderingModeOf(obj("ordering").string("mode"))
        return SchedulerSettings(
            orderingMode = mode,
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
            // The curated tunables are decimal strings in ms, given in every curated case (and read by no other mode).
            curated =
                CuratedTuning(
                    optional("repeatGapMs")?.toLong() ?: DEFAULT_REPEAT_GAP_MS,
                    optional("pairUpToMs")?.toLong() ?: DEFAULT_PAIR_UP_MS,
                ).takeIf { mode == OrderingMode.Curated },
            // The day cases of lineup.json to lineup-v3.json are rules of format 1 to 3: they gather the day end.
            leftover = LeftoverPolicy.Gather,
        )
    }

    private const val DEFAULT_REPEAT_GAP_MS = 10_800_000L
    private const val DEFAULT_PAIR_UP_MS = 2_100_000L

    // An unknown mode is rejected like any other bad input.
    private fun orderingModeOf(wireName: String): OrderingMode =
        OrderingMode.entries.firstOrNull { it.wireName == wireName }
            ?: throw IllegalArgumentException("unknown ordering mode $wireName")

    fun JsonObject.caps(): Caps = Caps(getValue("series").jsonPrimitive.int, getValue("film").jsonPrimitive.int)

    private fun JsonObject.runtimeFilter(): RuntimeFilter {
        val episode = this["episode"] as? JsonObject
        val movie = this["movie"] as? JsonObject
        return RuntimeFilter(
            minMs = minutes("minMinutes"),
            maxMs = minutes("maxMinutes"),
            episodeMinMs = episode?.minutes("minMinutes"),
            episodeMaxMs = episode?.minutes("maxMinutes"),
            movieMinMs = movie?.minutes("minMinutes"),
            movieMaxMs = movie?.minutes("maxMinutes"),
        )
    }

    private fun JsonObject.minutes(name: String): Long? = this[name]?.jsonPrimitive?.intOrNull?.let { it * MS_MINUTE }

    /** The weights of a case's settings (title ID to 1..3); absent means every title has weight 1. */
    fun JsonObject.weights(): TitleWeights =
        (this["weights"] as? JsonObject)
            ?.let { map -> TitleWeights.of(map.entries.associate { (id, w) -> canonical(id) to w.jsonPrimitive.int }) }
            ?: noWeights()

    /** The keeps of a case's settings (series ID to a cap in ms, a decimal string); absent means none. */
    fun JsonObject.keeps(): TitleKeeps =
        (this["keeps"] as? JsonObject)
            ?.let { map ->
                val caps = map.entries.associate { (id, cap) -> canonical(id) to cap.jsonPrimitive.content.toLong() }
                TitleKeeps.of(caps)
            }
            ?: noKeeps()

    /** The film-to-collection map of a case: `films` (rotation, capped shuffle) or `franchises` (curated). */
    fun JsonObject.filmMap(): FilmCollections = ((this["films"] ?: this["franchises"]) as? JsonObject).films()

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

    /** An item of a key-order file: the fields an order reads, and a runtime of 30 minutes unless it gives one. */
    fun JsonObject.keyOrderItem(): ScheduleItem =
        ScheduleItem(
            id = CanonicalId.parse(string("id")),
            kind = kindOf(string("kind")),
            runtimeMs = optional("runtimeMs")?.toLong() ?: (30 * MS_MINUTE),
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
            // The input is bad in one of three places: the settings, an item, or the day (buildDay). Each rejects it
            // with an IllegalArgumentException; any other exception is a fault in the case or the reader.
            var stage = "reading the settings"
            val thrown =
                runCatching {
                    val settings = case.obj("settings").settings()
                    stage = "reading the items"
                    val items = case.array("items").objects().map { it.item() }
                    stage = "building the day"
                    val raw = case.obj("settings")
                    buildDay(settings, items, case.string("n").toLong(), raw.filmMap(), raw.weights(), raw.keeps())
                }
            val error = thrown.exceptionOrNull()
            if (thrown.isSuccess) failures.add("[$name] input error: building should be rejected, but it built a day")
            if (error != null && error !is IllegalArgumentException) {
                failures.add("[$name] input error: $stage failed with $error, not an IllegalArgumentException")
            }
            return failures
        }
        val settings = case.obj("settings")
        val items = case.array("items").objects().map { it.item() }
        val day =
            buildDay(
                settings.settings(),
                items,
                case.string("n").toLong(),
                settings.filmMap(),
                settings.weights(),
                settings.keeps(),
            )
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
