// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.scheduler.vectors.KeyOrderVectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import tv.stationbreak.core.scheduler.vectors.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Run formation (spec 08, Unit 2). A run is the `blockLength` group played back to back. For `shuffle` and
 * `chronological` it is `blockLength` consecutive items counted from the start of the cycle (the last run may be
 * short, and runs never span the cycle's wrap). For `randomInOrder` one turn is one run.
 */
class RunFormationTest {
    /** [count] movies with IDs 0, 1, 2 ... (as 32 hex digits) and runtimes of 1, 2, 3 ... minutes. */
    private fun itemsOf(count: Int): List<ScheduleItem> =
        (0 until count).map {
            testItem(CanonicalId.parse(it.toString(16).padStart(32, '0')), runtimeMs = (it + 1) * MS_MINUTE)
        }

    private fun List<Run>.ids(): List<List<String>> = map { run -> run.items.map { it.id.value } }

    private fun lengths(
        count: Int,
        blockLength: Int,
    ): List<Int> = Runs.form(itemsOf(count), blockLength).map { it.items.size }

    @Test
    fun blockLengthOneMakesARunOfEveryItem() {
        assertEquals(listOf(1, 1, 1, 1, 1), lengths(5, 1))
    }

    @Test
    fun blockLengthTwoMakesRunsOfTwoAndAShortLastRun() {
        assertEquals(listOf(2, 2, 1), lengths(5, 2))
        assertEquals(listOf(2, 2), lengths(4, 2))
    }

    @Test
    fun blockLengthOneHundredMakesOneRunOfAPoolOfFewer() {
        assertEquals(listOf(7), lengths(7, 100))
        assertEquals(listOf(100, 1), lengths(101, 100))
        assertEquals(listOf(100), lengths(100, 100))
    }

    @Test
    fun aPoolSmallerThanARunIsOneShortRun() {
        assertEquals(listOf(2), lengths(2, 3))
        assertEquals(listOf(1), lengths(1, 2))
    }

    @Test
    fun anEmptyPoolHasNoRuns() {
        assertEquals(emptyList(), lengths(0, 3))
    }

    @Test
    fun runsAreCountedFromTheStartOfTheCycleAndNeverSpanTheWrap() {
        // 5 items, runs of 2: the cycle is [0 1][2 3][4]. The next cycle starts a new run at item 0, so item 4 does not
        // join item 0 into a run of two.
        val runs = Runs.form(itemsOf(5), 2)
        val indexes = runs.map { r -> r.items.map { it.id.value.toInt(16) } }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), indexes)
    }

    @Test
    fun aRunsRuntimeIsTheSumOfItsItemsRuntimes() {
        val runs = Runs.form(itemsOf(5), 2) // runtimes 1..5 minutes
        assertEquals(listOf(3 * MS_MINUTE, 7 * MS_MINUTE, 5 * MS_MINUTE), runs.map { it.runtimeMs })
    }

    @Test
    fun aRandomInOrderTurnIsOneRunWhateverTheGroupSizes() {
        val series = idOf('5')
        val eps =
            (1..3).map {
                testItem(CanonicalId.parse("a$it".padEnd(32, '0')), kind = ScheduleItemKind.Episode)
                    .copy(seriesId = series, seasonNumber = 1, episodeNumber = it)
            }
        val movie = testItem(idOf('9'))
        val settings = testSettings(orderingMode = OrderingMode.RandomInOrder, blockLength = 2, seed = Seed(3uL))
        val runs = Runs.cycle(settings, eps + movie, seedN = 0uL)
        val turns = Orderings.randomInOrderTurns(eps + movie, Seed(3uL), 2)
        assertEquals(turns.map { t -> t.map { it.id.value } }, runs.ids())
        assertEquals(setOf(1, 2), runs.map { it.items.size }.toSet())
        assertEquals(eps.size + 1, runs.sumOf { it.items.size })
    }

    @Test
    fun randomInOrderIgnoresTheEpochSeedAndShuffleUsesIt() {
        val pool = itemsOf(12)
        val random = testSettings(orderingMode = OrderingMode.RandomInOrder, seed = Seed(4uL))
        assertEquals(Runs.cycle(random, pool, seedN = 1uL).ids(), Runs.cycle(random, pool, seedN = 2uL).ids())
        val shuffle = testSettings(orderingMode = OrderingMode.Shuffle, seed = Seed(4uL))
        assertTrue(Runs.cycle(shuffle, pool, seedN = 1uL).ids() != Runs.cycle(shuffle, pool, seedN = 2uL).ids())
    }

    // The independent reference's cases: order and runs for every mode, from key-order.json.

    private val vectors = VectorJson.parse(KeyOrderVectorFile.text).array("vectors").objects()

    private fun JsonObject.pool(): List<ScheduleItem> =
        array("items").objects().map { o ->
            fun optional(name: String): String? = o[name]?.jsonPrimitive?.contentOrNull
            ScheduleItem(
                id = CanonicalId.parse(o.string("id")),
                kind = if (o.string("kind") == "movie") ScheduleItemKind.Movie else ScheduleItemKind.Episode,
                runtimeMs = 30 * MS_MINUTE,
                createdAtMs = 0L,
                deletedAtMs = null,
                premiereDate = optional("premiere"),
                productionYear = o["year"]?.jsonPrimitive?.intOrNull,
                seriesId = optional("seriesId")?.let { CanonicalId.parse(it) },
                seasonNumber = o["season"]?.jsonPrimitive?.intOrNull,
                episodeNumber = o["episode"]?.jsonPrimitive?.intOrNull,
            )
        }

    @Test
    fun everyVectorCaseFormsTheReferencesRuns() {
        for (case in vectors) {
            val mode = OrderingMode.entries.first { it.wireName == case.string("mode") }
            val seed = case["seed"]?.let { Seed(u64(it)) } ?: Seed(0uL)
            val seedN = case["seedN"]?.let { u64(it) } ?: 0uL
            val blockLength = case.string("blockLength").toInt()
            val settings = testSettings(orderingMode = mode, seed = seed, blockLength = blockLength)
            val expected = case.array("runs").map { run -> run.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(expected, Runs.cycle(settings, case.pool(), seedN).ids(), case.string("name"))
        }
    }
}
