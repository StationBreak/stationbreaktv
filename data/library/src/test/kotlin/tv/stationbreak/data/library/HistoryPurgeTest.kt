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
}
