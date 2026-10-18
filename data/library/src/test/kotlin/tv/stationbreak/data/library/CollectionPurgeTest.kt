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
 * Spec 10 task 2.6 (Vimes M6): the purge covers `collection`, `collection_version` and `collection_member` with the same
 * retention, clock guard and safeguards as items and series. A tombstone goes only when `deletedAtMs < cutoff` and
 * `seenFromMs < cutoff`; versions and member rows superseded more than the retention ago go first; a current member
 * row (null `supersededAtMs`) never goes; every table has its own count; another account is never touched.
 */
class CollectionPurgeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs
    private val thirtyDays = 30.days.inWholeMilliseconds
    private val box = ItemId("box01")

    @After
    fun close() = repository.close()

    private suspend fun purged() = repository.purgeHistory(ALICE)

    // Box 1: first seen at the start with {a, b}; one second later it gains c and loses a. That makes one version
    // (superseded at +1000) and one ended member row (a, superseded at +1000).
    private suspend fun editedCollection(account: tv.stationbreak.core.domain.LibraryAccount = ALICE) {
        repository.writeCollection(account, box, "x", movies("a", "b"))
        clock.stepMs(1_000)
        repository.writeCollection(account, box, "x", movies("b", "c"))
    }

    @Test
    fun aCollectionTombstoneThirtyDaysOldStaysAndOneMillisecondOlderGoes() =
        runBlocking {
            repository.writeCollection(ALICE, box, "x", movies("a"))
            repository.tombstoneCollections(ALICE, listOf(box))
            clock.stepMs(thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())
            assertEquals(1, repository.collections(ALICE).size)
            clock.stepMs(1)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0, collectionTombstones = 1), purged())
            assertEquals(emptyList<CollectionEntity>(), repository.collections(ALICE))
            // Its current members went with it, through the cascade.
            assertEquals(emptyList<CollectionMemberEntity>(), repository.collectionMembers(ALICE))
        }

    @Test
    fun aVersionSupersededThirtyDaysAgoStaysAndOneMillisecondOlderGoes() =
        runBlocking {
            editedCollection()
            clock.atMs(start + 1_000 + thirtyDays)

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0, collectionMembers = 0), purged())
            assertEquals(1, repository.collectionVersions(ALICE).size)
            clock.stepMs(1)

            val outcome = purged()

            assertEquals(1, (outcome as PurgeOutcome.Purged).collectionVersions)
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun aMemberRowSupersededThirtyDaysAgoStaysAndOneMillisecondOlderGoes() =
        runBlocking {
            editedCollection()
            clock.atMs(start + 1_000 + thirtyDays)
            assertEquals(3, repository.collectionMembers(ALICE).size)

            purged()
            assertEquals(3, repository.collectionMembers(ALICE).size)
            clock.stepMs(1)
            val outcome = purged()

            assertEquals(1, (outcome as PurgeOutcome.Purged).collectionMembers)
            assertEquals(listOf("b", "c"), repository.collectionMembers(ALICE).map { it.memberId })
        }

    @Test
    fun aCurrentMemberRowAndTheCurrentCollectionAreNeverPurgedHoweverOldTheyAre() =
        runBlocking {
            editedCollection()
            val collections = repository.collections(ALICE)
            val current = repository.collectionMembers(ALICE).filter { it.supersededAtMs == null }
            clock.stepMs(100.days.inWholeMilliseconds)

            purged()

            assertEquals(collections, repository.collections(ALICE))
            assertEquals(current, repository.collectionMembers(ALICE).filter { it.supersededAtMs == null })
        }

    @Test
    fun aTombstoneStampedBeforeTheCollectionWasSeenKeepsItsRecentHistory() =
        runBlocking {
            repository.writeCollection(ALICE, box, "x", movies("a"))
            clock.stepMs(1.days.inWholeMilliseconds)
            repository.writeCollection(ALICE, box, "x", movies("b")) // a version, and a ended, 'now'
            val seenFrom = clock.nowMs
            clock.atMs(seenFrom - 40.days.inWholeMilliseconds) // the clock is set back
            repository.tombstoneCollections(ALICE, listOf(box))
            clock.atMs(seenFrom + 1.days.inWholeMilliseconds) // deletedAt is 41 days old, the history 1 day old

            assertEquals(PurgeOutcome.Purged(tombstones = 0, versions = 0), purged())

            assertEquals(1, repository.collections(ALICE).size)
            assertEquals(1, repository.collectionVersions(ALICE).size)
            assertEquals(2, repository.collectionMembers(ALICE).size)
        }

    @Test
    fun theCountsAreOfEachTableAndAreTakenBeforeATombstoneTakesRowsWithIt() =
        runBlocking {
            editedCollection()
            repository.tombstoneCollections(ALICE, listOf(box))
            clock.stepMs(thirtyDays + 2_000)

            val outcome = purged()

            // The version and the ended member were superseded long ago, so they are counted as such, not as part of
            // the tombstone; the two current members go with the tombstone and are counted nowhere.
            val expected = PurgeOutcome.Purged(0, 0, collectionTombstones = 1, collectionVersions = 1, collectionMembers = 1)
            assertEquals(expected, outcome)
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun anotherAccountsCollectionsAreNeverTouched() =
        runBlocking {
            editedCollection()
            editedCollection(BOB)
            repository.tombstoneCollections(BOB, listOf(box))
            val bob = Triple(repository.collections(BOB), repository.collectionVersions(BOB), repository.collectionMembers(BOB))
            clock.stepMs(thirtyDays + 2_000)

            purged()

            assertEquals(bob, Triple(repository.collections(BOB), repository.collectionVersions(BOB), repository.collectionMembers(BOB)))
        }

    @Test
    fun aClockThatLooksWrongPurgesNoCollectionRows() =
        runBlocking {
            editedCollection()
            repository.tombstoneCollections(ALICE, listOf(box))
            val before = repository.rowCounts()
            clock.stepMs(100.days.inWholeMilliseconds)

            // The last sync was a day after the start; now is far ahead of it.
            val outcome = repository.purgeHistory(ALICE, Instant.fromEpochMilliseconds(start + 1.days.inWholeMilliseconds))

            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), outcome)
            assertEquals(before, repository.rowCounts())
        }

    // Vimes L6: for a cut-off older than the retention, rows the purge removed can make the member predicate give a
    // mixed set. This pins what it gives today; the safe range is the 72 h contract, as for items.
    @Test
    fun aCutoffOlderThanTheRetentionGivesTheDocumentedMixedSet() =
        runBlocking {
            repository.writeCollection(ALICE, box, "x", movies("a", "b")) // S0
            clock.stepMs(10.days.inWholeMilliseconds)
            repository.writeCollection(ALICE, box, "x", movies("b", "c")) // a leaves at S0 + 10 d
            clock.stepMs(40.days.inWholeMilliseconds)
            repository.writeCollection(ALICE, box, "x", movies("b", "c", "d")) // S0 + 50 d
            clock.stepMs(20.days.inWholeMilliseconds)
            val cutoff = Instant.fromEpochMilliseconds(start + 5.days.inWholeMilliseconds)
            val heldBefore =
                repository
                    .heldCollections(ALICE, cutoff)
                    .single()
                    .members
                    .map { it.memberId }
            assertEquals(listOf("a", "b"), heldBefore)

            purged() // S0 + 70 d: what was superseded before S0 + 40 d goes

            val mixed =
                repository
                    .heldCollections(ALICE, cutoff)
                    .single()
                    .members
                    .map { it.memberId }
            assertEquals("the oldest set lost the member the purge removed", listOf("b"), mixed)
        }
}
