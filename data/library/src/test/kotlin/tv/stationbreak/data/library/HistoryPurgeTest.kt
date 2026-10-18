// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 09 task 3.3 (T11, Vimes L1): the per-account purge removes tombstones, and versions that were superseded, more
 * than 30 days ago. The current version is never purged. Another account's rows are never touched. The history goes
 * with its item. Fake clock; `scheduleItems` safety is in `PurgeSafetyPropertyTest`.
 */
class HistoryPurgeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val thirtyDays = 30.days.inWholeMilliseconds

    @After
    fun close() = repository.close()

    private fun id(n: Int) = ItemId(hexId(n))

    // Items 1 to 4 for [account], each with genres and tags, all first seen at the current clock.
    private suspend fun fourItems(account: tv.stationbreak.core.domain.LibraryAccount = ALICE) {
        repository.select(account, setOf(MOVIES))
        val items = (1..4).map { sitem(it).copy(genres = listOf("g$it"), tags = listOf("t$it")) }
        repository.writePage(account, MOVIES, items, nextStartIndex = items.size, totalCount = items.size, run = 1)
    }

    private suspend fun rowIds(account: tv.stationbreak.core.domain.LibraryAccount = ALICE) =
        repository.stored(account).map { it.item.id.value }.sorted()

    private fun tagged(runtime: kotlin.time.Duration): MediaItem {
        val plain = sitem(1, runtime = runtime)
        return plain.copy(genres = listOf("g1"), tags = listOf("t1"))
    }

    private suspend fun purged() = repository.purgeHistory(ALICE)

    @Test
    fun aTombstoneThirtyDaysMinusOneMillisecondOldStays() =
        runBlocking {
            fourItems()
            repository.tombstone(ALICE, listOf(id(1)))
            clock.stepMs(thirtyDays - 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(4, rowIds().size)
        }

    @Test
    fun aTombstoneExactlyThirtyDaysOldStays() =
        runBlocking {
            fourItems()
            repository.tombstone(ALICE, listOf(id(1)))
            clock.stepMs(thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(4, rowIds().size)
        }

    @Test
    fun aTombstoneThirtyDaysPlusOneMillisecondOldGoes() =
        runBlocking {
            fourItems()
            repository.tombstone(ALICE, listOf(id(1)))
            clock.stepMs(thirtyDays + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), purged())
            assertEquals(listOf(hexId(2), hexId(3), hexId(4)), rowIds())
        }

    // Item 1: first seen at the start, edited 1 s later. The first version is superseded at start + 1000.
    private suspend fun oneEditedItem() {
        repository.select(ALICE, setOf(MOVIES))
        repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
        clock.stepMs(1_000)
        repository.put(ALICE, MOVIES, sitem(1, runtime = 91.minutes))
    }

    @Test
    fun aVersionSupersededThirtyDaysMinusOneMillisecondAgoStays() =
        runBlocking {
            oneEditedItem()
            clock.stepMs(thirtyDays - 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(1, repository.versions(ALICE).size)
        }

    @Test
    fun aVersionSupersededExactlyThirtyDaysAgoStays() =
        runBlocking {
            oneEditedItem()
            clock.stepMs(thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(1, repository.versions(ALICE).size)
        }

    @Test
    fun aVersionSupersededThirtyDaysPlusOneMillisecondAgoGoes() =
        runBlocking {
            oneEditedItem()
            clock.stepMs(thirtyDays + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 1), purged())
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
        }

    @Test
    fun theCurrentVersionAlwaysSurvivesHoweverOldItIs() =
        runBlocking {
            oneEditedItem()
            val before = repository.currentRows(ALICE)
            clock.stepMs(400.days.inWholeMilliseconds)

            purged()

            assertEquals(before, repository.currentRows(ALICE))
        }

    @Test
    fun onlyTheOlderOfTwoSupersededVersionsGoes() =
        runBlocking {
            oneEditedItem() // version 1 superseded at start + 1 s
            clock.stepMs(20.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 92.minutes)) // version 2 superseded at start + 20 d + 1 s
            clock.stepMs(10.days.inWholeMilliseconds) // version 1 was superseded exactly 30 d ago: it stays

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            clock.stepMs(1) // version 1 is now 30 d + 1 ms old; version 2 is 10 d + 1 ms old

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 1), purged())
            assertEquals(1, repository.versions(ALICE).size)
            assertEquals(91.minutes.inWholeMilliseconds, repository.versions(ALICE).single().runtimeMs)
        }

    @Test
    fun anotherAccountsRowsAreNeverTouched() =
        runBlocking {
            fourItems()
            fourItems(BOB)
            repository.tombstone(ALICE, listOf(id(1)))
            repository.tombstone(BOB, listOf(id(1)))
            repository.put(BOB, MOVIES, sitem(2, runtime = 91.minutes))
            clock.stepMs(1_000)
            repository.put(BOB, MOVIES, sitem(2, runtime = 92.minutes))
            val bobBefore = repository.currentRows(BOB) to repository.versions(BOB)
            clock.stepMs(thirtyDays + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), purged())

            assertEquals(bobBefore, repository.currentRows(BOB) to repository.versions(BOB))
            assertEquals(4, rowIds(BOB).size)
            assertEquals(3, rowIds().size)
        }

    @Test
    fun purgingATombstoneTakesItsGenresTagsAndVersionsWithIt() =
        runBlocking {
            fourItems()
            repository.put(ALICE, MOVIES, tagged(runtime = 91.minutes))
            clock.stepMs(1_000)
            repository.put(ALICE, MOVIES, tagged(runtime = 92.minutes))
            repository.tombstone(ALICE, listOf(id(1)))
            val rowsBefore = repository.rowCounts()
            clock.stepMs(thirtyDays + 1)

            purged()

            val after = repository.rowCounts()
            assertEquals(rowsBefore.items - 1, after.items)
            assertEquals(rowsBefore.genres - 1, after.genres)
            assertEquals(rowsBefore.tags - 1, after.tags)
            assertEquals(0, after.versions)
        }

    @Test
    fun liveItemsAreNeverPurgedHoweverOldTheClockGets() =
        runBlocking {
            fourItems()
            clock.stepMs(400.days.inWholeMilliseconds)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(4, rowIds().size)
        }

    // Vimes L-2 on #268: the same repository, the account signed out with its rows still there, so a purge that
    // forgot requireActive would delete them.
    @Test
    fun anAccountThatIsNotSignedInIsRefusedAndNothingIsDeleted() =
        runBlocking {
            fourItems()
            repository.put(ALICE, MOVIES, sitem(2, runtime = 91.minutes))
            clock.stepMs(1_000)
            repository.put(ALICE, MOVIES, sitem(2, runtime = 92.minutes))
            repository.tombstone(ALICE, listOf(id(1)))
            clock.stepMs(thirtyDays + 1)
            repository.dao.deleteActive(ALICE.server.value, ALICE.user.value)

            assertThrows(AccountNotActiveException::class.java) { runBlocking { repository.purgeHistory(ALICE) } }

            assertEquals(4, rowIds().size)
            assertEquals(2, repository.versions(ALICE).size)
            assertEquals(1, repository.stored(ALICE).count { it.deletedAt != null })
        }

    // Vimes L-1 on #268: a tombstone set while the clock was behind the item's own stamp has deletedAt < seenFrom.
    // The versions it holds were superseded inside the retention, so the purge must leave the whole item.
    @Test
    fun aTombstoneStampedBeforeTheItemWasSeenKeepsItsRecentHistory() =
        runBlocking {
            fourItems()
            clock.stepMs(1.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 91.minutes)) // version 1 superseded now; seenFrom = now
            val seenFrom = clock.nowMs
            clock.atMs(seenFrom - 40.days.inWholeMilliseconds) // the clock is set back
            repository.tombstone(ALICE, listOf(id(1)))
            clock.atMs(seenFrom + 1.days.inWholeMilliseconds) // deletedAt is 41 days old, the version 1 day old

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())

            assertEquals(4, rowIds().size)
            assertEquals(1, repository.versions(ALICE).size)
        }

    @Test
    fun aTombstoneStampedBeforeTheItemWasSeenGoesOnceTheItemIsOldToo() =
        runBlocking {
            fourItems()
            clock.stepMs(1.days.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 91.minutes))
            val seenFrom = clock.nowMs
            clock.atMs(seenFrom - 40.days.inWholeMilliseconds)
            repository.tombstone(ALICE, listOf(id(1)))
            clock.atMs(seenFrom + thirtyDays + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 1), purged())
            assertEquals(3, rowIds().size)
        }

    @Test
    fun theRetentionIsTheOneTheRepositoryWasGiven() =
        runBlocking {
            val short = TestLibraries.inMemory(clock, retention = HistoryRetention(days = 5))
            try {
                short.select(ALICE, setOf(MOVIES))
                short.writePage(ALICE, MOVIES, listOf(sitem(1)), nextStartIndex = 1, totalCount = 1, run = 1)
                short.tombstone(ALICE, listOf(id(1)))
                clock.stepMs(5.days.inWholeMilliseconds + 1)

                assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), short.purgeHistory(ALICE))
            } finally {
                short.close()
            }
        }

    // Spec 10 task 1.8: the same purge for series and series_version, with the same retention and the same safeguards.

    private fun sid(n: Int) = ItemId(hexId(n))

    private suspend fun fourSeries(account: tv.stationbreak.core.domain.LibraryAccount = ALICE) {
        repository.select(account, setOf(SHOWS))
        val rows = (1..4).map { series(hexId(it), "TV-14", listOf("g$it"), listOf("t$it")) }
        repository.upsertSeries(account, SHOWS, rows)
    }

    // Series 1: first seen at the start, edited 1 s later. The first version is superseded at start + 1000.
    private suspend fun oneEditedSeries() {
        repository.select(ALICE, setOf(SHOWS))
        repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(1), "TV-14")))
        clock.stepMs(1_000)
        repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(1), "TV-MA")))
    }

    @Test
    fun aSeriesTombstoneThirtyDaysOldStaysAndOneMillisecondOlderGoes() =
        runBlocking {
            fourSeries()
            repository.tombstoneSeries(ALICE, listOf(sid(1)))
            clock.stepMs(thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(4, repository.currentSeries(ALICE).size)
            clock.stepMs(1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0, seriesTombstones = 1), purged())
            assertEquals(listOf(hexId(2), hexId(3), hexId(4)), repository.currentSeries(ALICE).map { it.seriesId })
        }

    @Test
    fun aSeriesVersionSupersededThirtyDaysAgoStaysAndOneMillisecondOlderGoes() =
        runBlocking {
            oneEditedSeries()
            clock.stepMs(thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(1, repository.seriesVersions(ALICE).size)
            clock.stepMs(1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0, seriesVersions = 1), purged())
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
        }

    @Test
    fun theCurrentSeriesRowIsNeverPurgedHoweverOldItIs() =
        runBlocking {
            oneEditedSeries()
            val before = repository.currentSeries(ALICE)
            clock.stepMs(400.days.inWholeMilliseconds)

            purged()

            assertEquals(before, repository.currentSeries(ALICE))
        }

    @Test
    fun theCountsAreOfEachTableAndVersionsAreCountedBeforeATombstoneTakesThemWithIt() =
        runBlocking {
            fourItems()
            repository.tombstone(ALICE, listOf(id(1)))
            oneEditedSeries() // series 1: one version, superseded at start + 1000
            repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(2)), series(hexId(3), "G")))
            repository.tombstoneSeries(ALICE, listOf(sid(1))) // its old version goes with the tombstone
            clock.stepMs(thirtyDays + 2_000)

            val outcome = purged()

            // The version was superseded long ago, so it is counted as a version, not as part of the tombstone.
            val expected = PurgeOutcome.Purged(tombstones = 1, versions = 0, seriesTombstones = 1, seriesVersions = 1)
            assertEquals(expected, outcome)
            assertEquals(0, repository.rowCounts().seriesVersions)
        }

    @Test
    fun aSeriesTombstoneStampedBeforeTheSeriesWasSeenKeepsItsRecentHistory() =
        runBlocking {
            fourSeries()
            clock.stepMs(1.days.inWholeMilliseconds)
            repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(1), "TV-MA"))) // version 1 superseded now
            val seenFrom = clock.nowMs
            clock.atMs(seenFrom - 40.days.inWholeMilliseconds) // the clock is set back
            repository.tombstoneSeries(ALICE, listOf(sid(1)))
            clock.atMs(seenFrom + 1.days.inWholeMilliseconds) // deletedAt is 41 days old, the version 1 day old

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())

            assertEquals(4, repository.currentSeries(ALICE).size)
            assertEquals(1, repository.seriesVersions(ALICE).size)
        }

    @Test
    fun anotherAccountsSeriesAreNeverTouched() =
        runBlocking {
            fourSeries()
            fourSeries(BOB)
            repository.tombstoneSeries(ALICE, listOf(sid(1)))
            repository.tombstoneSeries(BOB, listOf(sid(1)))
            repository.upsertSeries(BOB, SHOWS, listOf(series(hexId(2), "R")))
            val bobBefore = repository.currentSeries(BOB) to repository.seriesVersions(BOB)
            clock.stepMs(thirtyDays + 1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0, seriesTombstones = 1), purged())

            assertEquals(bobBefore, repository.currentSeries(BOB) to repository.seriesVersions(BOB))
            assertEquals(3, repository.currentSeries(ALICE).size)
        }

    @Test
    fun anAccountThatIsNotSignedInLosesNoSeries() =
        runBlocking {
            oneEditedSeries()
            repository.tombstoneSeries(ALICE, listOf(sid(1)))
            clock.stepMs(thirtyDays + 2_000)
            repository.dao.deleteActive(ALICE.server.value, ALICE.user.value)

            assertThrows(AccountNotActiveException::class.java) { runBlocking { repository.purgeHistory(ALICE) } }

            assertEquals(1, repository.currentSeries(ALICE).size)
            assertEquals(1, repository.seriesVersions(ALICE).size)
        }
}
