// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.CollectionCallKind
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 2.5: a nested BoxSet member is skipped and counted, never stored and never followed; other types and IDs
 * that are not canonical are skipped and counted too. The member calls are one per listed BoxSet, so no call can have
 * flattened a nested one (the adapter never sends `Recursive=true` on them: CollectionRequestShapeTest).
 */
class NestedCollectionTest {
    private val rig = CollectionRig()

    @After
    fun close() = rig.repository.close()

    private fun member(
        n: Int,
        kind: CollectionMemberKind,
    ) = CollectionMember(rig.film(n), kind)

    @Test
    fun aNestedCollectionIsSkippedAndCountedAndNotStored() =
        runBlocking {
            rig.serve(
                1,
                member(10, CollectionMemberKind.Movie),
                CollectionMember(rig.box(2), CollectionMemberKind.Collection),
                member(11, CollectionMemberKind.Series),
            )

            val result = rig.run()

            assertEquals(1, result.summary.skippedNested)
            assertEquals(
                listOf("${rig.film(10).value}:movie", "${rig.film(11).value}:series"),
                rig.repository.currentMembers(ALICE, rig.box(1).value),
            )
        }

    @Test
    fun aNestedCollectionIsNeverFollowed() =
        runBlocking {
            val nested = CollectionMember(rig.box(2), CollectionMemberKind.Collection)
            rig.serve(1, member(10, CollectionMemberKind.Movie), nested)
            // BoxSet 2 is not listed at the top (say it is nested only), so nothing may ask for its members.
            rig.run()

            val asked =
                rig.server.collectionCalls
                    .filter { it.kind == CollectionCallKind.Members }
                    .map { it.collection }
            assertEquals(listOf(rig.box(1)), asked.distinct())
        }

    @Test
    fun otherTypesAndNonCanonicalIdsAreSkippedAndCounted() =
        runBlocking {
            rig.serve(
                1,
                member(10, CollectionMemberKind.Episode),
                member(11, CollectionMemberKind.Other),
                member(12, CollectionMemberKind.Season),
                CollectionMember(ItemId("not-an-id"), CollectionMemberKind.Movie),
            )

            val result = rig.run()

            assertEquals(1, result.summary.skippedOther)
            assertEquals(1, result.summary.skippedIds)
            assertEquals(0, result.summary.skippedNested)
            assertEquals(2, rig.current(1).size)
        }

    @Test
    fun aHyphenatedUpperCaseIdIsStoredInItsCanonicalForm() =
        runBlocking {
            val dashed = "ABCDEF01-2345-6789-ABCD-EF0123456789"
            rig.serve(1, CollectionMember(ItemId(dashed), CollectionMemberKind.Movie))

            rig.run()

            val stored = rig.repository.currentMembers(ALICE, rig.box(1).value)
            assertEquals(listOf("abcdef0123456789abcdef0123456789:movie"), stored)
        }
}
