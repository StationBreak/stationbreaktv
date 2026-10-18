// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * Spec 10 task 2.5 (pool universe, rules-format rule 5): a member that lives outside the selected libraries is stored as
 * an ID, and is in the collection's member set; it simply never matches an item of the snapshot.
 */
class OutsideLibraryMemberTest {
    private val rig = CollectionRig()

    @After
    fun close() = rig.repository.close()

    @Test
    fun aMemberWithNoItemInAnyLibraryIsStoredAndHeld() =
        runBlocking {
            rig.repository.select(ALICE, setOf(MOVIES))
            rig.repository.put(ALICE, MOVIES, sitem(1))
            // film 1 is cached; film 2 is in no ticked library.
            rig.serve(1, *rig.movies(1, 2))

            rig.run()

            assertEquals(listOf(rig.film(1).value, rig.film(2).value), rig.current(1))
            val held = rig.repository.heldCollections(ALICE, Instant.fromEpochMilliseconds(rig.clock.nowMs + 1))
            assertEquals(listOf(rig.film(1).value, rig.film(2).value), held.single().members.map { it.memberId })
        }
}
