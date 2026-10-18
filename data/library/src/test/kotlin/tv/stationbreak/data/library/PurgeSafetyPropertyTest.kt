// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 09 task 3.5 (Vimes L-1 on #264): the purge is safe for every cut-off a coming day can need, and only for those.
 *
 * - **The property.** A seeded generator makes random histories (new items, runtime, genre and library edits,
 *   tombstones and revivals, spread over 90 days with a cluster in the last four). For cut-offs from `now - 72 h` on,
 *   `scheduleItems` returns the same list before and after a purge at `now`. A failure prints the case number and the
 *   seed: rerun it with `PurgeGen(<seed>)`.
 * - **The contract.** The safe range is `cutoff >= now - 72 h` (`HistoryRetention.SAFE_LOOKBACK_MS`), documented on
 *   the port's `scheduleItems` and not enforced by a `require`: see that KDoc for why. A cut-off older than the
 *   retention is shown to lose items, so the range is pinned from both sides.
 * - **The metric.** After 60 simulated days of daily edits, the versions kept per item are the ones superseded in the
 *   last 30 days, and they stop growing.
 */
class PurgeSafetyPropertyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val libraries = setOf(MOVIES, SHOWS)

    @After
    fun close() = repository.close()

    private class PurgeGen(
        val seed: ULong,
    ) {
        private var state = seed

        fun nextLong(bound: Long): Long {
            val out = splitmix64(state)
            state += GAMMA
            return (out % bound.toULong()).toLong()
        }

        fun nextInt(bound: Int): Int = nextLong(bound.toLong()).toInt()

        fun chance(percent: Int): Boolean = nextInt(PERCENT) < percent

        companion object {
            const val GAMMA = 0x9E3779B97F4A7C15uL
            const val PERCENT = 100
        }
    }

    private val now = EPOCH
    private val nowMs = now.toEpochMilliseconds()
    private val safeFromMs = nowMs - HistoryRetention.SAFE_LOOKBACK_MS

    // Times for one case, ascending: 90 days back to now, a third of them in the last four days.
    private fun timesOf(gen: PurgeGen): List<Long> =
        List(OPS_PER_CASE) {
            val span = if (gen.chance(35)) 4.days.inWholeMilliseconds else NINETY_DAYS_MS
            nowMs - gen.nextLong(span)
        }.sorted()

    // One step of a case: a random op at [atMs], or, when [n] and [kind] are given, that op on that item.
    private class Step(
        val atMs: Long,
        val n: Int? = null,
        val kind: Int? = null,
    )

    // The ops every case also has (Vimes L-3 on #268), on items of their own, at the edges of the two ranges: the safe
    // edge (now - 72 h) and the retention edge (now - 30 d). Kind 7 always changes the item (a new genre name).
    private fun fixedSteps(): List<Step> {
        val retentionEdge = nowMs - HistoryRetention().millis
        val longAgo = nowMs - 40.days.inWholeMilliseconds
        return listOf(
            // 9: an edit at exactly the retention edge, and one at exactly the safe edge.
            Step(longAgo, 9, EDIT),
            Step(retentionEdge, 9, EDIT),
            Step(safeFromMs, 9, EDIT),
            // 10: a tombstone at exactly the retention edge.
            Step(longAgo, 10, EDIT),
            Step(retentionEdge, 10, TOMBSTONE),
            // 11: deleted long ago, revived 1 ms after the safe edge.
            Step(longAgo, 11, EDIT),
            Step(nowMs - 35.days.inWholeMilliseconds, 11, TOMBSTONE),
            Step(safeFromMs + 1, 11, REVIVE),
            // 12: a recent history that the clock step back below stamps a tombstone before.
            Step(nowMs - 2.days.inWholeMilliseconds, 12, EDIT),
            Step(nowMs - 1.days.inWholeMilliseconds, 12, EDIT),
        )
    }

    // The clock is set back 40 days and item 12 is deleted: its tombstone is older than the item's own stamp, so only
    // its seenFrom keeps its recent versions out of the purge (Vimes L-1 on #268).
    private suspend fun steppedBackTombstone() {
        clock.atMs(nowMs - 40.days.inWholeMilliseconds)
        repository.tombstone(ALICE, listOf(ItemId(hexId(12))))
    }

    // One random op at [atMs] on one of eight items. A library move is a write into the other library.
    private suspend fun apply(
        gen: PurgeGen,
        step: Step,
    ) {
        clock.atMs(step.atMs)
        val n = step.n ?: (1 + gen.nextInt(ITEMS))
        val id = ItemId(hexId(n))
        when (step.kind ?: gen.nextInt(OP_KINDS)) {
            EDIT -> repository.put(ALICE, MOVIES, sitem(n).copy(genres = listOf("f${step.atMs}")))
            TOMBSTONE -> repository.tombstone(ALICE, listOf(id))
            REVIVE -> repository.revive(ALICE, listOf(id))
            0, 1 -> repository.put(ALICE, MOVIES, sitem(n, runtime = (30 + gen.nextInt(120)).minutes))
            2 -> repository.put(ALICE, MOVIES, sitem(n).copy(genres = listOf("g${gen.nextInt(GENRES)}")))
            3 -> repository.put(ALICE, SHOWS, sitem(n, library = SHOWS))
            4 -> repository.tombstone(ALICE, listOf(id))
            else -> repository.revive(ALICE, listOf(id))
        }
    }

    private suspend fun read(cutoffMs: Long): List<ScheduleItem> {
        val cutoff = Instant.fromEpochMilliseconds(cutoffMs)
        return repository.scheduleItems(ALICE, libraries, cutoff)
    }

    private suspend fun readAll(cutoffs: List<Long>) = cutoffs.map { read(it) }

    @Test
    fun aPurgeChangesNothingAnyCutOffFromSeventyTwoHoursBackCanSee() =
        runBlocking {
            var purgedSomething = 0
            for (case in 0 until CASES) {
                val seed = BASE_SEED + case.toULong()
                val gen = PurgeGen(seed)
                repository.forget(ALICE)
                repository.useAccount(ALICE)
                repository.select(ALICE, libraries)
                val steps = (timesOf(gen).map { Step(it) } + fixedSteps()).sortedBy { it.atMs }
                steps.forEach { apply(gen, it) }
                steppedBackTombstone()
                clock.atMs(nowMs)
                val fixed = listOf(safeFromMs, safeFromMs + 1, nowMs - 36 * HOUR_MS, nowMs, nowMs + HOUR_MS)
                val cutoffs = fixed + List(RANDOM_CUTOFFS) { safeFromMs + gen.nextLong(5.days.inWholeMilliseconds) }
                val before = readAll(cutoffs)

                val outcome = repository.purgeHistory(ALICE) as PurgeOutcome.Purged

                if (outcome.tombstones + outcome.versions > 0) purgedSomething++
                assertEquals("case $case, seed $seed", before, readAll(cutoffs))
            }
            // The property is not vacuous: most cases had something old to purge.
            assertTrue("only $purgedSomething of $CASES cases purged anything", purgedSomething > CASES / 2)
        }

    @Test
    fun theSafeRangeIsShorterThanTheRetention() {
        assertTrue(HistoryRetention.SAFE_LOOKBACK_MS < HistoryRetention().millis)
        assertEquals(72.hours.inWholeMilliseconds, HistoryRetention.SAFE_LOOKBACK_MS)
    }

    @Test
    fun aCutOffOlderThanTheRetentionIsOutsideTheContractAndLosesTheOldestCopy() =
        runBlocking {
            // First seen 60 days ago, edited 50 days ago and 10 days ago: two versions, [-60, -50) and [-50, -10).
            repository.select(ALICE, libraries)
            clock.atMs(nowMs - 60.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 60.minutes))
            clock.atMs(nowMs - 50.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 70.minutes))
            clock.atMs(nowMs - 10.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 80.minutes))
            clock.atMs(nowMs)
            val old = nowMs - 55.days.inWholeMilliseconds
            assertEquals(60.minutes.inWholeMilliseconds, read(old).single().runtimeMs)
            val safe = readAll(listOf(safeFromMs, nowMs))

            repository.purgeHistory(ALICE)

            // The version superseded 50 days ago is gone, and with it case (c)'s probe of firstSeenMs: the read falls
            // back to the current row, so a cut-off that old is answered with today's copy (80 minutes), not the held
            // one (60). Inside the safe range nothing changed.
            assertEquals(80.minutes.inWholeMilliseconds, read(old).single().runtimeMs)
            assertEquals(safe, readAll(listOf(safeFromMs, nowMs)))
        }

    @Test
    fun sixtyDaysOfDailyEditsKeepOnlyTheVersionsSupersededInTheLastThirtyDays() =
        runBlocking {
            repository.select(ALICE, libraries)
            val retentionMs = HistoryRetention().millis
            val sizes = mutableListOf<Int>()
            repeat(SIMULATED_DAYS) { day ->
                clock.atMs(nowMs + day.days.inWholeMilliseconds)
                (1..ITEMS).forEach { n -> repository.put(ALICE, MOVIES, sitem(n, runtime = (60 + day).minutes)) }
                repository.purgeHistory(ALICE)
                sizes += repository.versions(ALICE).size
                // Every kept version was superseded within the retention.
                repository.versions(ALICE).forEach {
                    assertTrue("day $day: ${it.supersededAtMs}", it.supersededAtMs >= clock.nowMs - retentionMs)
                }
            }

            val perItem = repository.versions(ALICE).groupBy { it.itemId }.mapValues { it.value.size }
            assertEquals(ITEMS, perItem.size)
            // A daily edit supersedes one version a day. One superseded exactly 30 days ago is kept (the cut is
            // strict), so 31 at most, and the current row is on top of them.
            perItem.values.forEach { assertTrue("kept $it versions", it <= RETENTION_DAYS + 1) }
            // The history has stopped growing: the last 20 days added nothing.
            assertEquals(sizes[SIMULATED_DAYS - 21], sizes.last())
        }

    // Spec 10 task 1.8: the same property for series. There is no series as-of read yet (spec 10 Unit 3), so the test
    // holds its own: the version of each series this TV held before a cut-off, by the same three cases as the items'.

    private data class HeldSeries(
        val id: String,
        val rating: String?,
        val genres: String,
        val tags: String,
    )

    private suspend fun heldSeriesAsOf(cutoffMs: Long): List<HeldSeries> {
        val versions = repository.seriesVersions(ALICE).groupBy { it.seriesId }
        return repository.currentSeries(ALICE).mapNotNull { row ->
            val own = versions[row.seriesId].orEmpty()
            val held =
                if (row.seenFromMs < cutoffMs) {
                    HeldSeries(row.seriesId, row.officialRating, row.genres, row.tags) to row.deletedAtMs
                } else {
                    val covering = own.firstOrNull { it.seenFromMs < cutoffMs && it.supersededAtMs >= cutoffMs }
                    val oldest = own.firstOrNull { it.seenFromMs == row.firstSeenMs }
                    val version = covering ?: oldest
                    if (version != null) {
                        val held = HeldSeries(row.seriesId, version.officialRating, version.genres, version.tags)
                        held to version.deletedAtMs
                    } else {
                        HeldSeries(row.seriesId, row.officialRating, row.genres, row.tags) to row.deletedAtMs
                    }
                }
            held.first.takeIf { held.second == null || held.second!! >= cutoffMs }
        }
    }

    private fun oneOf(gen: PurgeGen) = "v${gen.nextInt(GENRES)}"

    private suspend fun applySeries(
        gen: PurgeGen,
        step: Step,
    ) {
        clock.atMs(step.atMs)
        val n = step.n ?: (1 + gen.nextInt(ITEMS))
        val id = ItemId(hexId(n))
        when (step.kind ?: gen.nextInt(OP_KINDS)) {
            EDIT -> repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(n), genres = listOf("f${step.atMs}"))))
            TOMBSTONE -> repository.tombstoneSeries(ALICE, listOf(id))
            REVIVE -> repository.reviveSeries(ALICE, listOf(id))
            0, 1 -> repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(n), "R${gen.nextInt(GENRES)}")))
            2 -> repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(n), genres = listOf(oneOf(gen)))))
            3 -> repository.upsertSeries(ALICE, MOVIES, listOf(series(hexId(n), tags = listOf(oneOf(gen)))))
            4 -> repository.tombstoneSeries(ALICE, listOf(id))
            else -> repository.reviveSeries(ALICE, listOf(id))
        }
    }

    @Test
    fun aPurgeChangesNothingAnyCutOffFromSeventyTwoHoursBackCanSeeInTheSeries() =
        runBlocking {
            var purgedSomething = 0
            for (case in 0 until CASES) {
                val seed = BASE_SEED + 0x5E71E5uL + case.toULong()
                val gen = PurgeGen(seed)
                repository.forget(ALICE)
                repository.useAccount(ALICE)
                repository.select(ALICE, libraries)
                val steps = (timesOf(gen).map { Step(it) } + fixedSteps()).sortedBy { it.atMs }
                steps.forEach { applySeries(gen, it) }
                steppedBackSeriesTombstone()
                clock.atMs(nowMs)
                val fixed = listOf(safeFromMs, safeFromMs + 1, nowMs - 36 * HOUR_MS, nowMs, nowMs + HOUR_MS)
                val cutoffs = fixed + List(RANDOM_CUTOFFS) { safeFromMs + gen.nextLong(5.days.inWholeMilliseconds) }
                val before = cutoffs.map { heldSeriesAsOf(it) }

                val outcome = repository.purgeHistory(ALICE) as PurgeOutcome.Purged

                if (outcome.seriesTombstones + outcome.seriesVersions > 0) purgedSomething++
                assertEquals("case $case, seed $seed", before, cutoffs.map { heldSeriesAsOf(it) })
            }
            assertTrue("only $purgedSomething of $CASES cases purged a series row", purgedSomething > CASES / 2)
        }

    // As for items: the clock is set back 40 days and series 12 is deleted, so only its seenFrom keeps its recent
    // versions out of the purge.
    private suspend fun steppedBackSeriesTombstone() {
        clock.atMs(nowMs - 40.days.inWholeMilliseconds)
        repository.tombstoneSeries(ALICE, listOf(ItemId(hexId(12))))
    }

    private companion object {
        const val EDIT = 7
        const val TOMBSTONE = 4
        const val REVIVE = 5
        const val HOUR_MS = 3_600_000L
        const val CASES = 300
        const val BASE_SEED = 0x5EED_0009uL
        const val OPS_PER_CASE = 40
        const val ITEMS = 8
        const val GENRES = 3
        const val OP_KINDS = 6
        const val RANDOM_CUTOFFS = 4
        const val SIMULATED_DAYS = 60
        const val RETENTION_DAYS = 30
        val NINETY_DAYS_MS = 90.days.inWholeMilliseconds
    }
}
