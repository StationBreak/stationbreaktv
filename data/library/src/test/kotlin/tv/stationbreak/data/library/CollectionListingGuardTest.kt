// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.CollectionCallKind
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 2.5 (Vimes H3): the collection pass reads BoxSet listings, BoxSet ID listings and member lists under the
 * item reconcile's guards. A read counts only when every page was read and the IDs read equal the first page's total; an
 * empty listing while collections are stored is suspect; before a collection is tombstoned, or a non-empty member list
 * emptied, the list is read a second time and only what both reads lack is removed. A failed or untrusted read changes
 * nothing.
 */
class CollectionListingGuardTest {
    private val rig = CollectionRig(pageSize = 2)
    private val repository get() = rig.repository

    @After
    fun close() = repository.close()

    private suspend fun stored() = repository.collections(ALICE).map { it.collectionId to it.deletedAtMs }

    private suspend fun seed() {
        (1..5).forEach { rig.serve(it, *rig.movies(it * 10, it * 10 + 1)) }
        assertEquals(SyncOutcome.Completed, rig.run().outcome)
        rig.clock.stepMs(60_000)
    }

    private suspend fun assertNothingChanged(
        before: List<Pair<String, Long?>>,
        members: List<CollectionMemberEntity>,
    ) {
        assertEquals(before, stored())
        assertEquals(members, repository.collectionMembers(ALICE))
    }

    @Test
    fun anUnchangedServerTombstonesNothingAndWritesNothing() =
        runBlocking {
            seed()
            val before = repository.collectionMembers(ALICE)
            val versions = repository.collectionVersions(ALICE)

            val result = rig.run()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.tombstoned)
            assertEquals(5, result.summary.unchanged)
            assertEquals(before, repository.collectionMembers(ALICE))
            assertEquals(versions, repository.collectionVersions(ALICE))
        }

    @Test
    fun aGoneCollectionIsTombstonedAfterASecondRead() =
        runBlocking {
            seed()
            rig.source.collections.removeAll { it.id == rig.box(3) }
            rig.server.collectionCalls.clear()

            val result = rig.run()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(1, result.summary.tombstoned)
            assertEquals(rig.clock.nowMs, repository.collections(ALICE).single { it.collectionId == rig.box(3).value }.deletedAtMs)
            // Two reads of the ID listing: the first found it missing, the second confirmed it.
            val idReads = rig.server.collectionCalls.filter { it.kind == CollectionCallKind.Ids && it.startIndex == 0 }
            assertEquals(2, idReads.size)
        }

    @Test
    fun aCollectionTheSecondReadStillListsIsNotTombstoned() =
        runBlocking {
            seed()
            val gone = rig.source.collections.single { it.id == rig.box(3) }
            rig.source.collections.remove(gone)
            var reads = 0
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Ids && it.startIndex == 0 && ++reads == 2) rig.source.collections += gone
                null
            }

            val result = rig.run()

            assertEquals(0, result.summary.tombstoned)
            assertNull(repository.collections(ALICE).single { it.collectionId == rig.box(3).value }.deletedAtMs)
        }

    @Test
    fun aFailedListingPageChangesNothing() =
        runBlocking {
            seed()
            val before = stored()
            val members = repository.collectionMembers(ALICE)
            rig.source.collections.removeAll { it.id == rig.box(1) }
            rig.serve(9, *rig.movies(90))
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Listing && it.startIndex > 0) MediaServerError.Timeout() else null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertNothingChanged(before, members)
        }

    @Test
    fun aFailedIdListingPageChangesNothing() =
        runBlocking {
            seed()
            val before = stored()
            val members = repository.collectionMembers(ALICE)
            rig.source.collections.removeAll { it.id == rig.box(1) }
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Ids && it.startIndex > 0) MediaServerError.ServerError() else null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertNothingChanged(before, members)
        }

    @Test
    fun aShortCountTombstonesNothing() =
        runBlocking {
            seed()
            val before = stored()
            val members = repository.collectionMembers(ALICE)
            // The second ID page loses a collection that page one's total counted.
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Ids && it.startIndex == 2) rig.source.collections.removeAt(4)
                null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertNothingChanged(before, members)
        }

    @Test
    fun aShiftedPageTombstonesNothing() =
        runBlocking {
            seed()
            val before = stored()
            val members = repository.collectionMembers(ALICE)
            // A collection is added in front between the pages, so page two repeats one and the last is never reached.
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Ids && it.startIndex == 2) {
                    rig.source.collections.add(0, CollectionInfo(ItemId(hexId(1)), "New"))
                }
                null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertNothingChanged(before, members)
        }

    @Test
    fun anEmptyListingWhileCollectionsAreStoredIsSuspectAndTombstonesNothing() =
        runBlocking {
            seed()
            val before = stored()
            val members = repository.collectionMembers(ALICE)
            rig.source.collections.clear()

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), result.outcome)
            assertNothingChanged(before, members)
        }

    @Test
    fun anEmptyListingWhenNothingIsStoredIsFine() =
        runBlocking {
            assertEquals(SyncOutcome.Completed, rig.run().outcome)
            assertEquals(emptyList<CollectionEntity>(), repository.collections(ALICE))
        }

    @Test
    fun aFailedMemberReadInsertsNoCollection() =
        runBlocking {
            rig.serve(1, *rig.movies(10, 11))
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Members) MediaServerError.Timeout() else null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertEquals(emptyList<CollectionEntity>(), repository.collections(ALICE))
            assertEquals(emptyList<CollectionMemberEntity>(), repository.collectionMembers(ALICE))
        }

    @Test
    fun aMemberListWithAShortCountChangesNothing() =
        runBlocking {
            seed()
            val members = repository.collectionMembers(ALICE)
            rig.serve(1, *rig.movies(10, 11, 12, 13, 14))
            // The second member page loses a member that page one's total counted.
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Members && it.startIndex == 2) {
                    rig.source.members
                        .getValue(rig.box(1))
                        .removeAt(4)
                }
                null
            }

            val result = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertEquals(members, repository.collectionMembers(ALICE))
        }

    @Test
    fun aNonEmptyMemberListThatReadsEmptyIsReadAgainAndKeptIfTheSecondReadHasMembers() =
        runBlocking {
            seed()
            val saved =
                rig.source.members
                    .getValue(rig.box(2))
                    .toList()
            rig.source.members
                .getValue(rig.box(2))
                .clear()
            var reads = 0
            rig.server.respondToCollections = {
                if (it.kind == CollectionCallKind.Members && it.collection == rig.box(2) && it.startIndex == 0 && ++reads == 2) {
                    rig.source.members.getValue(rig.box(2)) += saved
                }
                null
            }

            rig.run()

            assertEquals(2, reads)
            assertEquals(listOf(rig.film(20).value, rig.film(21).value), rig.current(2).map { it })
        }

    @Test
    fun aMemberListThatReadsEmptyTwiceIsEmptied() =
        runBlocking {
            seed()
            rig.source.members
                .getValue(rig.box(2))
                .clear()

            rig.run()

            assertEquals(emptyList<String>(), rig.current(2))
        }

    @Test
    fun aListedTombstoneIsRevivedAndItsMembersReReadUnderOneStamp() =
        runBlocking {
            seed()
            val gone = rig.source.collections.single { it.id == rig.box(4) }
            rig.source.collections.remove(gone)
            rig.run()
            rig.clock.stepMs(60_000)
            rig.source.collections += gone
            rig.source.members.getValue(rig.box(4)) += rig.movies(99)

            val result = rig.run()

            assertEquals(1, result.summary.revived)
            val row = repository.collections(ALICE).single { it.collectionId == rig.box(4).value }
            assertNull(row.deletedAtMs)
            assertEquals(rig.clock.nowMs, row.seenFromMs)
            val added = repository.collectionMembers(ALICE).single { it.memberId == rig.film(99).value }
            assertEquals(row.seenFromMs, added.seenFromMs)
            assertNotNull(
                repository.collectionVersions(ALICE).singleOrNull { it.collectionId == row.collectionId && it.deletedAtMs != null },
            )
        }
}
