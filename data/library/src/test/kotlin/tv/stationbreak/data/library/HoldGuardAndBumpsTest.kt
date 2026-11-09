// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.repair.DEFAULT_PAUSE_HOLD
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Task 4.5 (binding items 5 and 6): the hold-expiry clock guard is sized to a hold, so a forward jump of hours
 * ends no hold early; and deleting a library moves the content generation only when it holds rows. Beside
 * [ApplySelectionTest]. Invented libraries and names only.
 */
class HoldGuardAndBumpsTest {
    private val clock = FakeClock()
    private val lines = mutableListOf<String>()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE)) { lines += it }
    private val extra = ItemId(hexId(0xB003))

    @After
    fun close() = repository.close()

    private suspend fun rows() = repository.dao.libraries("server-1", "alice").associateBy { ItemId(it.libraryId) }

    private suspend fun row(library: ItemId) = rows()[library]

    private suspend fun generation() = repository.contentGeneration(ALICE)

    private suspend fun storedIn(library: ItemId) = repository.stored(ALICE).filter { it.library == library }

    private suspend fun apply(
        keep: Set<ItemId>,
        addStamps: Map<ItemId, Long> = emptyMap(),
        holds: Map<ItemId, Long> = emptyMap(),
    ) = repository.applySelection(ALICE, keep, addStamps, holds, clock.nowMs)

    // One item in [library] and its checkpoint, as a page of the first sync leaves them; then the library is fetched.
    private suspend fun fill(
        library: ItemId,
        n: Int,
    ) {
        repository.writePage(
            ALICE,
            library,
            stamp = null,
            rewalk = false,
            listOf(sitem(n, library = library)),
            1,
            1,
            run = clock.nowMs,
        )
        repository.markWalked(ALICE, library)
    }

    // Binding item 5 (Task 4.5): a forward jump of a few hours, far short of the purge's 27 days but longer than a
    // hold, does not end a hold early.
    @Test
    fun aForwardJumpLongerThanAHold_butShortOfThePurgeGuard_expiresNoHold() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            repository.finishSync(ALICE)
            val until = clock.nowMs + DEFAULT_PAUSE_HOLD.inWholeMilliseconds
            apply(setOf(MOVIES), holds = mapOf(SHOWS to until))
            val before = generation()

            clock.stepMs(3.hours.inWholeMilliseconds)
            apply(setOf(MOVIES))

            assertEquals("the hold is kept", until, row(SHOWS)?.heldUntilMs)
            assertEquals("and its rows", 1, storedIn(SHOWS).size)
            assertEquals(before, generation())
            assertEquals("said, as a number only", listOf("HoldExpiryFrozen sinceSuccessMin=180"), lines)
        }

    // Fix round 1: a call that expires nothing for the guard says nothing.
    @Test
    fun aHoldStillAhead_isNotSaidToBeFrozen() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            repository.finishSync(ALICE)
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 4.hours.inWholeMilliseconds))

            clock.stepMs(3.hours.inWholeMilliseconds)
            apply(setOf(MOVIES))

            assertEquals(emptyList<String>(), lines)
        }

    // Binding item 6 (Task 4.5): unticking a library still pending with no rows (set up, never walked) changes no
    // day, so it moves no generation.
    @Test
    fun unticking_aPendingLibraryWithNoRows_movesNoGeneration() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(MOVIES, 1)
            val before = generation()

            apply(setOf(MOVIES))

            assertNull(row(SHOWS))
            assertEquals(before, generation())
        }

    // Binding item 6 (Task 4.5): an add that was held (so it lost its stamp) and expires with no rows moves no
    // generation; one with rows still does.
    @Test
    fun aHeldAddExpiringWithNoRows_movesNoGeneration_oneWithRowsDoes() =
        runBlocking {
            apply(setOf(MOVIES))
            fill(MOVIES, 1)
            apply(setOf(MOVIES, SHOWS, extra), addStamps = mapOf(SHOWS to 3L, extra to 3L))
            repository.writePage(
                ALICE,
                extra,
                stamp = 3L,
                rewalk = false,
                listOf(sitem(4, library = extra)),
                1,
                1,
                run = clock.nowMs,
            )
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 1_000, extra to clock.nowMs + 2_000))
            val before = generation()

            clock.stepMs(1_000)
            apply(setOf(MOVIES))
            assertNull(row(SHOWS))
            assertEquals("a held add with no rows", before, generation())

            clock.stepMs(1_000)
            apply(setOf(MOVIES))
            assertNull(row(extra))
            assertEquals("a held add with rows", before + 1, generation())
        }
}
