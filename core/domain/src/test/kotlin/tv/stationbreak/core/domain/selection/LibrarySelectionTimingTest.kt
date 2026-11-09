// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The time a change takes, by step (the Libraries screen's slow untick on a TV): the repair of today (`record`), the
 * settings save (`save`), the library cache's write (`cache`), the wait for the lock (`wait`) and the whole (`total`),
 * each reported once per change as numbers only.
 */
internal class LibrarySelectionTimingTest : SelectionTestBase() {
    @Test
    fun anUntick_reportsEachStep_soASlowOneShows() {
        val tv = Tv().ready(setOf(libA, libB))
        // Each step made slow on its own, by a different amount, so the report shows which one it was.
        tv.rig.library.afterSnapshot = { Thread.sleep(RECORD_MS) }
        tv.settings.beforeSave = { Thread.sleep(SAVE_MS) }
        tv.library.beforeApply = { Thread.sleep(CACHE_MS) }

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))

        val took = tv.changeTimes.single()
        assertTrue(took.recordMs >= RECORD_MS, "the repair's reads: $took")
        assertTrue(took.saveMs in SAVE_MS until RECORD_MS, "the save alone, not the repair around it: $took")
        assertTrue(took.cacheMs >= CACHE_MS, "the cache write: $took")
        assertTrue(took.waitMs >= 0, "$took")
        assertTrue(took.totalMs >= took.recordMs + took.saveMs + took.cacheMs, "$took")
    }

    @Test
    fun aChangeRefused_isReportedToo_withNoStepsTaken() {
        val tv = Tv(chosen = setOf(libA)).ready(setOf(libA))

        assertEquals(ChangeResult.LastLibrary, tv.change(emptySet()))

        val took = tv.changeTimes.single()
        assertEquals(0L, took.recordMs + took.saveMs + took.cacheMs, "$took")
        assertTrue(took.totalMs >= 0)
    }

    @Test
    fun theLine_isNumbersOnly() {
        val line = ChangeTiming(waitMs = 1, recordMs = 2, saveMs = 3, cacheMs = 4, totalMs = 10).line()

        assertEquals("SelectionChangeTook wait=1 record=2 save=3 cache=4 total=10", line)
    }

    private companion object {
        const val RECORD_MS = 400L
        const val SAVE_MS = 150L
        const val CACHE_MS = 250L
    }
}
