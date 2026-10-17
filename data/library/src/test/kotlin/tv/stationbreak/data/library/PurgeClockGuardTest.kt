// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.days

/**
 * Spec 09 task 3.6 (Vimes L2, and #254's clock items): a device whose clock looks wrong must not purge. When `now` is
 * before the last successful sync, or more than the retention period ahead of it, the purge is skipped, deletes
 * nothing, and says so. The last success is the moment spec 06's stay-current work already records.
 */
class PurgeClockGuardTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val retentionMs = 30.days.inWholeMilliseconds
    private val tombstone = ItemId(hexId(1))

    @After
    fun close() = repository.close()

    // Item 1 was deleted 5 days before EPOCH; the last sync succeeded at EPOCH. Item 2 has two versions.
    private suspend fun syncedAtEpoch() {
        repository.select(ALICE, setOf(MOVIES))
        repository.writePage(ALICE, MOVIES, listOf(sitem(1), sitem(2)), nextStartIndex = 2, totalCount = 2, run = 1)
        clock.atMs(EPOCH.toEpochMilliseconds() - 5.days.inWholeMilliseconds)
        repository.tombstone(ALICE, listOf(tombstone))
        clock.atMs(EPOCH.toEpochMilliseconds())
        repository.finishSync(ALICE)
    }

    private suspend fun rowsLeft() = repository.stored(ALICE).size

    @Test
    fun aClockBeforeTheLastSuccessSkipsThePurgeAndDeletesNothing() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(EPOCH.toEpochMilliseconds() - 1)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockBehindLastSync), repository.purgeHistory(ALICE))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aClockSetBackFarEnoughThatEverythingLooksOldIsStillSkipped() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(EPOCH.toEpochMilliseconds() - 400.days.inWholeMilliseconds)

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
    fun aClockMoreThanTheRetentionAheadOfTheLastSuccessSkipsThePurge() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(EPOCH.toEpochMilliseconds() + retentionMs + 1)

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), repository.purgeHistory(ALICE))
            assertEquals(2, rowsLeft())
        }

    @Test
    fun aClockExactlyTheRetentionAheadOfTheLastSuccessPurges() =
        runBlocking {
            syncedAtEpoch()
            clock.atMs(EPOCH.toEpochMilliseconds() + retentionMs)

            // The tombstone is 35 days old by now.
            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), repository.purgeHistory(ALICE))
            assertEquals(1, rowsLeft())
        }

    @Test
    fun withNoSuccessRecordedThereIsNothingToCompareWithAndThePurgeRuns() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(sitem(1)), nextStartIndex = 1, totalCount = 1, run = 1)
            repository.tombstone(ALICE, listOf(tombstone))
            clock.stepMs(retentionMs + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), repository.purgeHistory(ALICE))
        }

    @Test
    fun theGuardUsesTheRetentionTheRepositoryWasGiven() =
        runBlocking {
            val short = TestLibraries.inMemory(clock, retention = HistoryRetention(days = 5))
            try {
                short.select(ALICE, setOf(MOVIES))
                short.finishSync(ALICE)
                clock.stepMs(5.days.inWholeMilliseconds + 1)

                assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), short.purgeHistory(ALICE))
            } finally {
                short.close()
            }
        }
}
