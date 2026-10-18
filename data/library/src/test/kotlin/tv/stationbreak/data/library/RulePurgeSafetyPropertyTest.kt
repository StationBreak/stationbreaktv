// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ordering.splitmix64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 10 task 3.4.2 (Vimes M6): the purge is safe for every cut-off a coming day can need, for the whole rule
 * snapshot. After `purgeHistory` at `now`, `ruleSnapshot` for every cut-off from `now - 72 h`
 * ([HistoryRetention.SAFE_LOOKBACK_MS]) on returns exactly what it returned before.
 *
 * A seeded generator makes 300 histories of items, series and collections over 90 days, with a cluster in the last
 * four, and every case also has the fixed operations at the edges of the two ranges (the retention edge, now - 30 d,
 * and the safe edge, now - 72 h):
 * - an item and a series edited exactly at each edge;
 * - a collection member superseded exactly at the retention edge, and one at the safe edge;
 * - a collection tombstoned exactly at the retention edge;
 * - a collection deleted long ago and revived 1 ms after the safe edge;
 * - a collection whose tombstone is stamped by a clock set back 40 days, after its recent members.
 *
 * **The mutation check.** The same history is purged at a mutated time, 28 days late, so the cut-offs that were safe
 * are now older than the purge's own safe range. The same comparison must then find a difference in most cases, and
 * does, so the property cannot pass because the comparison is blind or the history is empty.
 */
class RulePurgeSafetyPropertyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val libraries = setOf(RULE_LIBRARY, RuleLibraryGen.LIBRARY_B)
    private val nowMs = EPOCH.toEpochMilliseconds()
    private val safeFromMs = nowMs - HistoryRetention.SAFE_LOOKBACK_MS
    private val retentionEdgeMs = nowMs - HistoryRetention().millis
    private val longAgoMs = nowMs - 40.days.inWholeMilliseconds

    @After
    fun close() = repository.close()

    private class Step(
        val atMs: Long,
        val run: suspend () -> Unit,
    )

    private fun box(n: Int) = ItemId(hexId(n))

    private fun members(vararg numbers: Int): List<CollectionMember> {
        val kind = CollectionMemberKind.Episode
        return numbers.map { CollectionMember(ItemId(hexId(it)), kind) }
    }

    private suspend fun writeBox(
        n: Int,
        vararg numbers: Int,
    ) = repository.writeCollection(ALICE, box(n), "Name of $n", members(*numbers))

    // The fixed operations, each on entities of its own.
    private fun fixedSteps(): List<Step> =
        listOf(
            Step(longAgoMs) { repository.put(ALICE, RULE_LIBRARY, episodeOf(9, 910, rating = "G")) },
            Step(retentionEdgeMs) { repository.put(ALICE, RULE_LIBRARY, episodeOf(9, 910, rating = "PG")) },
            Step(safeFromMs) { repository.put(ALICE, RULE_LIBRARY, episodeOf(9, 910, rating = "R")) },
            Step(longAgoMs) { repository.putSeries(ALICE, RULE_LIBRARY, series(hexId(910), "G")) },
            Step(retentionEdgeMs) { repository.putSeries(ALICE, RULE_LIBRARY, series(hexId(910), "PG")) },
            Step(safeFromMs) { repository.putSeries(ALICE, RULE_LIBRARY, series(hexId(910), "R")) },
            // A member superseded exactly at the retention edge, and one exactly at the safe edge.
            Step(longAgoMs) { writeBox(1001, 1, 2) },
            Step(retentionEdgeMs) { writeBox(1001, 2) },
            Step(nowMs - 10.days.inWholeMilliseconds) { writeBox(1002, 1, 2) },
            Step(safeFromMs) { writeBox(1002, 2) },
            // A tombstone at the retention edge.
            Step(longAgoMs) { writeBox(1003, 1) },
            Step(retentionEdgeMs) { repository.tombstoneCollections(ALICE, listOf(box(1003))) },
            // Deleted long ago, revived 1 ms after the safe edge.
            Step(longAgoMs) { writeBox(1004, 1) },
            Step(nowMs - 35.days.inWholeMilliseconds) { repository.tombstoneCollections(ALICE, listOf(box(1004))) },
            Step(safeFromMs + 1) { writeBox(1004, 1) },
            // Recent members, which the clock step back below stamps a tombstone before.
            Step(nowMs - 2.days.inWholeMilliseconds) { writeBox(1005, 1) },
            Step(nowMs - 1.days.inWholeMilliseconds) { writeBox(1005, 1, 2) },
        )

    // The clock is set back 40 days and the collection deleted: its tombstone is older than its own stamp, so only its
    // seenFrom keeps its recent members out of the purge.
    private suspend fun steppedBackTombstone() {
        clock.atMs(longAgoMs)
        repository.tombstoneCollections(ALICE, listOf(box(1005)))
    }

    // One random operation at [atMs] on one of a few items, series and collections.
    private suspend fun randomStep(
        gen: SeededGen,
        atMs: Long,
    ) {
        clock.atMs(atMs)
        val n = 1 + gen.nextInt(ITEMS)
        val library = if (gen.chance(70)) RULE_LIBRARY else RuleLibraryGen.LIBRARY_B
        val show = SERIES_BASE + gen.nextInt(SERIES)
        val boxId = box(BOXES_BASE + gen.nextInt(BOXES))
        when (gen.nextInt(OP_KINDS)) {
            0, 1 -> {
                repository.put(ALICE, library, episodeOf(n, show, library, rating = gen.pick(RuleLibraryGen.RATINGS)))
            }

            2 -> {
                val genres = listOf("g${gen.nextInt(GENRES)}")
                repository.put(ALICE, library, episodeOf(n, show, library).copy(genres = genres))
            }

            3 -> {
                repository.tombstone(ALICE, listOf(ItemId(hexId(n))))
            }

            4 -> {
                repository.revive(ALICE, listOf(ItemId(hexId(n))))
            }

            5 -> {
                repository.putSeries(
                    ALICE,
                    library,
                    series(hexId(show), gen.pick(RuleLibraryGen.RATINGS), listOf("v${gen.nextInt(GENRES)}")),
                )
            }

            6 -> {
                if (gen.chance(
                        50,
                    )
                ) {
                    repository.tombstoneSeries(
                        ALICE,
                        listOf(ItemId(hexId(show))),
                    )
                } else {
                    repository.reviveSeries(ALICE, listOf(ItemId(hexId(show))))
                }
            }

            7 -> {
                val numbers = IntArray(gen.nextInt(MEMBERS)) { 1 + gen.nextInt(ITEMS) }
                repository.writeCollection(ALICE, boxId, "Name", members(*numbers))
            }

            else -> {
                repository.tombstoneCollections(ALICE, listOf(boxId))
            }
        }
    }

    private suspend fun build(gen: SeededGen) {
        repository.forget(ALICE)
        repository.useAccount(ALICE)
        repository.select(ALICE, libraries)
        val randomSteps =
            List(OPS_PER_CASE) {
                val span = if (gen.chance(35)) 4.days.inWholeMilliseconds else NINETY_DAYS_MS
                nowMs - gen.nextLong(span)
            }
        val steps = (randomSteps.map { at -> Step(at) { randomStep(gen, at) } } + fixedSteps()).sortedBy { it.atMs }
        for (step in steps) {
            clock.atMs(step.atMs)
            step.run()
        }
        steppedBackTombstone()
        clock.atMs(nowMs)
    }

    private fun cutoffsOf(gen: SeededGen): List<Long> {
        val fixed = listOf(safeFromMs, safeFromMs + 1, nowMs - 36 * HOUR_MS, nowMs, nowMs + HOUR_MS)
        return fixed + List(RANDOM_CUTOFFS) { safeFromMs + gen.nextLong(5.days.inWholeMilliseconds) }
    }

    private suspend fun readAll(cutoffs: List<Long>): List<RuleSnapshot> {
        val read = ArrayList<RuleSnapshot>()
        for (cutoff in cutoffs) read += repository.ruleSnapshot(ALICE, libraries, at(cutoff))
        return read
    }

    @Test
    fun aPurgeChangesNothingAnyCutOffFromSeventyTwoHoursBackCanSee() =
        runBlocking {
            var purgedSomething = 0
            for (case in 0 until CASES) {
                val seed = splitmix64(BASE_SEED + case.toULong())
                val gen = SeededGen(seed)
                build(gen)
                val cutoffs = cutoffsOf(gen)
                val before = readAll(cutoffs)

                val outcome = repository.purgeHistory(ALICE) as PurgeOutcome.Purged

                val versions = outcome.versions + outcome.collectionVersions
                if (versions + outcome.collectionMembers + outcome.tombstones > 0) purgedSomething++
                assertEquals("case $case, seed $seed", before, readAll(cutoffs))
            }
            // The property is not vacuous: most cases had something old to purge.
            assertTrue("only $purgedSomething of $CASES cases purged anything", purgedSomething > CASES / 2)
        }

    @Test
    fun theSafeRangeIsTheSeventyTwoHoursTheItemReadUses() {
        assertEquals(72.hours.inWholeMilliseconds, HistoryRetention.SAFE_LOOKBACK_MS)
    }

    @Test
    fun aPurgeThatRunsTwentyEightDaysLateIsCaughtByTheSameComparison() =
        runBlocking {
            var caught = 0
            for (case in 0 until MUTATION_CASES) {
                val gen = SeededGen(splitmix64(BASE_SEED + case.toULong()))
                build(gen)
                val cutoffs = cutoffsOf(gen)
                val before = readAll(cutoffs)

                // The mutation: the purge's clock is 28 days ahead, so cut-offs from now - 72 h are 28 days outside
                // its safe range.
                clock.atMs(nowMs + 28.days.inWholeMilliseconds)
                repository.purgeHistory(ALICE)

                if (before != readAll(cutoffs)) caught++
            }
            println("RulePurgeSafetyPropertyTest: the mutated purge was caught in $caught of $MUTATION_CASES cases")
            val message = "the mutated purge was caught in only $caught of $MUTATION_CASES cases"
            assertTrue(message, caught > MUTATION_CASES / 2)
        }

    @Test
    fun theFixedOperationsLeaveTheSnapshotAloneButAMutatedPurgeLosesAMember() =
        runBlocking {
            repository.select(ALICE, libraries)
            fixedSteps().sortedBy { it.atMs }.forEach {
                clock.atMs(it.atMs)
                it.run()
            }
            clock.atMs(nowMs)
            val cutoffs = listOf(safeFromMs, safeFromMs + 1, nowMs)
            val before = readAll(cutoffs)
            // At the safe edge the collection whose member was superseded exactly there still holds it.
            assertEquals(setOf(1, 2), heldNumbers(before[0], 1002))

            repository.purgeHistory(ALICE)
            assertEquals(before, readAll(cutoffs))

            clock.atMs(nowMs + 28.days.inWholeMilliseconds)
            repository.purgeHistory(ALICE)
            assertTrue("a late purge changed nothing", before != readAll(cutoffs))
        }

    private fun heldNumbers(
        snapshot: RuleSnapshot,
        box: Int,
    ): Set<Int> =
        snapshot.collections
            .getValue(canon(box))
            .episodes
            .map { it.value.toInt(HEX) }
            .toSet()

    private companion object {
        const val CASES = 300
        const val MUTATION_CASES = 100
        const val BASE_SEED = 0x5EED_1005uL
        const val OPS_PER_CASE = 40
        const val NINETY_DAYS_MS = 90L * 24 * 3_600_000
        const val HOUR_MS = 3_600_000L
        const val RANDOM_CUTOFFS = 6
        const val ITEMS = 8
        const val SERIES = 3
        const val SERIES_BASE = 900
        const val BOXES = 3
        const val BOXES_BASE = 1100
        const val MEMBERS = 6
        const val GENRES = 4
        const val OP_KINDS = 9
        const val HEX = 16
    }
}
