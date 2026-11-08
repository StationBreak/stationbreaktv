// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hold expiry through the selection (spec 26, Part 4 D12, Task 4.5): stay current asks for it after each completed
 * pass. It is the cache write of the stored selection with no new holds, so the same move code runs, under the
 * selection's lock and after its resume. Invented libraries only.
 */
internal class LibrarySelectionExpiryTest : SelectionTestBase() {
    private val holdMs = 10_000L

    // A TV choosing libA, with libB held in the cache until [holdMs] from now (as an untick during a pin leaves it).
    private fun heldTv(): Tv {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA, libB))
        tv.resume()
        val stored = assertNotNull(tv.stored())
        val unticked = stored.copy(chosen = setOf(libA), seq = stored.seq + 1)
        assertTrue(runBlocking { tv.settings.inner.saveSelection(unticked) })
        val holds = mapOf(libB to tv.nowMs + holdMs)
        runBlocking { tv.fake.applySelection(ACCOUNT, setOf(libA), holds = holds, nowMs = tv.nowMs) }
        assertNotNull(tv.row(libB)?.heldUntilMs, "libB is held")
        return tv
    }

    @Test
    fun expireHolds_deletesAHoldThatEnded_andPlacesNoNewHold() {
        val tv = heldTv()
        tv.nowMs += holdMs
        val applied = tv.library.applied.size

        runBlocking { tv.selection.expireHolds(ACCOUNT) }

        assertNull(tv.row(libB), "the ended hold is gone")
        assertNotNull(tv.row(libA))
        val write =
            tv.library.applied
                .drop(applied)
                .single()
        assertEquals(setOf(libA), write.keep)
        assertEquals(emptyMap(), write.holds, "no new hold: the pins on air are not asked for")
    }

    @Test
    fun expireHolds_keepsAHoldStillAhead() {
        val tv = heldTv()
        tv.nowMs += holdMs - 1

        runBlocking { tv.selection.expireHolds(ACCOUNT) }

        assertNotNull(tv.row(libB)?.heldUntilMs, "the hold is still ahead")
    }

    @Test
    fun expireHolds_withNoHeldRow_writesNothing() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        val calls = tv.library.applyCalls

        runBlocking { tv.selection.expireHolds(ACCOUNT) }

        assertEquals(calls, tv.library.applyCalls)
    }

    @Test
    fun expireHolds_forAnAccountNotActive_writesNothing() {
        val tv = heldTv()
        tv.nowMs += holdMs
        val calls = tv.library.applyCalls

        runBlocking { tv.selection.expireHolds(other) }

        assertEquals(calls, tv.library.applyCalls)
        assertNotNull(tv.row(libB))
    }

    // Through the selection's entry: it waits for the resume at start, as every operation does.
    @Test
    fun expireHolds_waitsForTheResume() {
        val tv = heldTv()
        val fresh = tv.restartedSelection()
        tv.nowMs += holdMs

        runBlocking {
            val expiring = async(start = CoroutineStart.UNDISPATCHED) { fresh.expireHolds(ACCOUNT) }
            yield()
            assertFalse(expiring.isCompleted, "it waits for the resume")
            assertNotNull(tv.row(libB))
            fresh.resumeAtStart()
            expiring.await()
        }

        assertNull(tv.row(libB))
    }
}
