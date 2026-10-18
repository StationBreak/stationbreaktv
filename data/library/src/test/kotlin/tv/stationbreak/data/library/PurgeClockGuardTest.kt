// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Spec 09 task 3.6 (Vimes L2, and #254's clock items): a device whose clock looks wrong must not purge. When `now` is
 * before the reference (the last successful sync), or more than the retention minus the 72 h safe look-back ahead of
 * it, the purge is skipped, deletes nothing, and says so. The reference is the moment spec 06's stay-current work
 * recorded, read by the caller before the run overwrote it (Vimes M-1 on #268). The limit is 27 days, so a purge at the
 * limit still leaves a coming day's 72 h inside the retention.
 */
class PurgeClockGuardTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val retentionMs = 30.days.inWholeMilliseconds
    private val limitMs = retentionMs - HistoryRetention.SAFE_LOOKBACK_MS
    private val epochMs = EPOCH.toEpochMilliseconds()
    private val tombstone = ItemId(hexId(1))

    @After
    fun close() = repository.close()

    // Item 1 was deleted 5 days before EPOCH; the last sync succeeded at EPOCH. Item 2 is live.
    private suspend fun syncedAtEpoch() {
        repository.select(ALICE, setOf(MOVIES))
        clock.atMs(epochMs - 10.days.inWholeMilliseconds)
        repository.writePage(ALICE, MOVIES, listOf(sitem(1), sitem(2)), nextStartIndex = 2, totalCount = 2, run = 1)
        clock.atMs(epochMs - 5.days.inWholeMilliseconds)
        repository.tombstone(ALICE, listOf(tombstone))
        clock.atMs(epochMs)
        repository.finishSync(ALICE)
    }

    private suspend fun rowsLeft() = repository.stored(ALICE).size

    @Test
    fun aClockBeforeTheLastSuccessSkipsThePurgeAndDeletesNothing() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(epochMs - 1)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockBehindLastSync), repository.purgeHistory(ALICE))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aClockSetBackFarEnoughThatEverythingLooksOldIsStillSkipped() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(epochMs - 400.days.inWholeMilliseconds)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockBehindLastSync), repository.purgeHistory(ALICE))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aClockExactlyAtTheLastSuccessPurges() =
        runBlocking {
            syncedAtEpoch()
            // A tombstone 5 days old is younger than 30 days: it stays, and the purge ran.
            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), repository.purgeHistory(ALICE))
        }

    @Test
    fun theFarAheadLimitIsTheRetentionMinusTheSafeLookBack() {
        assertEquals(27.days.inWholeMilliseconds, limitMs)
    }

    @Test
    fun aClockMoreThanTheLimitAheadOfTheLastSuccessSkipsThePurge() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(epochMs + limitMs + 1)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), repository.purgeHistory(ALICE))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aClockExactlyTheLimitAheadOfTheLastSuccessPurges() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(epochMs + limitMs)

            // The tombstone is 32 days old by now.
            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), repository.purgeHistory(ALICE))
            assertEquals(1, rowsLeft())
        }

    @Test
    fun theReferenceIsTheOneTheCallerGaveNotTheOneStoredNow() =
        runBlocking {
            syncedAtEpoch()
            // The stored success is fresh (a run just overwrote it), but the reference read before the run was old.
            clock.atMs(epochMs + 40.days.inWholeMilliseconds)
            repository.finishSync(ALICE)
            val stale = Instant.fromEpochMilliseconds(epochMs)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), repository.purgeHistory(ALICE, stale))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aReferenceTwentySevenDaysAndOneMillisecondBackIsSkippedAndTwentySevenDaysIsNot() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(epochMs + 40.days.inWholeMilliseconds)
            val now = clock.nowMs

            val tooOld = Instant.fromEpochMilliseconds(now - limitMs - 1)
            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), repository.purgeHistory(ALICE, tooOld))
            val atTheLimit = Instant.fromEpochMilliseconds(now - limitMs)
            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), repository.purgeHistory(ALICE, atTheLimit))
        }

    @Test
    fun withNoReferenceThereIsNothingToCompareWithAndThePurgeRuns() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(sitem(1)), nextStartIndex = 1, totalCount = 1, run = 1)
            repository.tombstone(ALICE, listOf(tombstone))
            clock.stepMs(retentionMs + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), repository.purgeHistory(ALICE, null))
        }

    @Test
    fun theGuardUsesTheRetentionTheRepositoryWasGiven() =
        runBlocking {
            val short = TestLibraries.inMemory(clock, retention = HistoryRetention(days = 5))
            try {
                short.select(ALICE, setOf(MOVIES))
                short.finishSync(ALICE)
                // 5 days minus 72 h is 2 days.
                clock.stepMs(2.days.inWholeMilliseconds + 1)

                assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), short.purgeHistory(ALICE))
            } finally {
                short.close()
            }
        }
}
