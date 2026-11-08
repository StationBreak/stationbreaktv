// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 26, Part 4 (schema 11): `applySelection`'s additions to [ApplySelectionTest]. A hold for a library already held
 * keeps the later of the two (D5); a library leaving `keep` loses its stamp, its walk's proof and its rewalk mark; a
 * held library re-ticked with no stamp keeps the coverage its hold set. Invented libraries only.
 */
class ApplySelectionLifecycleTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private suspend fun row(library: ItemId) = repository.dao.libraries("server-1", "alice").associateBy { ItemId(it.libraryId) }[library]

    private suspend fun fill(
        library: ItemId,
        n: Int,
    ) {
        repository.put(ALICE, library, sitem(n, library = library))
        repository.markWalked(ALICE, library)
    }

    private suspend fun apply(
        keep: Set<ItemId>,
        addStamps: Map<ItemId, Long> = emptyMap(),
        holds: Map<ItemId, Long> = emptyMap(),
    ) = repository.applySelection(ALICE, keep, addStamps, holds, clock.nowMs)

    @Test
    fun holds_takeMax() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            val later = clock.nowMs + 60_000
            apply(setOf(MOVIES), holds = mapOf(SHOWS to later))

            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 10_000))
            assertEquals("an earlier hold never shortens one in place", later, row(SHOWS)?.heldUntilMs)

            apply(setOf(MOVIES), holds = mapOf(SHOWS to later + 5_000))
            assertEquals("a later one extends it", later + 5_000, row(SHOWS)?.heldUntilMs)
        }

    @Test
    fun leavingKeep_clearsMark() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 3L))
            assertTrue(repository.revertUnproven(ALICE, SHOWS, 3L))
            assertTrue(checkNotNull(row(SHOWS)).rewalkPending)

            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 60_000))

            val held = checkNotNull(row(SHOWS))
            assertFalse("a held library is never rewalked", held.rewalkPending)
            assertNull(held.walkedStamp)
            assertNull(held.addStamp)

            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 4L))
            assertTrue(repository.markAddWalked(ALICE, SHOWS, 4L, clock.nowMs))
            apply(setOf(MOVIES))
            assertNull("dropped with no hold: the row goes, mark and proof with it", row(SHOWS))
        }

    @Test
    fun reTickHeldWithoutStamp_keepsCoveredFrom() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            val heldAt = clock.nowMs
            apply(setOf(MOVIES), holds = mapOf(SHOWS to heldAt + 60_000))
            assertEquals(heldAt, row(SHOWS)?.coveredFromMs)
            clock.stepMs(5_000)

            apply(setOf(MOVIES, SHOWS))

            val reTicked = checkNotNull(row(SHOWS))
            assertNull(reTicked.heldUntilMs)
            assertEquals("its next incremental reads from when it was held", heldAt, reTicked.coveredFromMs)
            assertTrue("and it is still fetched", reTicked.fetched)
        }
}
