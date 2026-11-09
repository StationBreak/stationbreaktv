// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The time a change takes, by step (the Libraries screen's slow untick on a TV): the wait for the lock (`wait`), the
 * wait for the repairer's turn (`turnWait`), the repair of today (`record`), the settings save (`save`), the steps
 * after it (`cache`) and the whole (`total`), each reported once per change as numbers only. The selection's clock is
 * the rig's, which moves only when a step here moves it, so every number is exact.
 */
internal class LibrarySelectionTimingTest : SelectionTestBase() {
    // Moves the clock by [ms] the first time it is called, and never again.
    private fun Tv.once(ms: Long): () -> Unit {
        var done = false
        return {
            if (!done) {
                done = true
                ticks += ms.milliseconds
            }
        }
    }

    @Test
    fun anUntick_reportsEachStep_soASlowOneShows() {
        val tv = Tv().ready(setOf(libA, libB))
        // Each step made slow by its own amount, so the report shows which one it was. The first read of the cache's
        // rows in a change is the floor read just before the repairer's turn is asked for.
        val beforeTurn = tv.once(TURN_MS)
        val repair = tv.once(RECORD_MS)
        val save = tv.once(SAVE_MS)
        val cache = tv.once(CACHE_MS)
        tv.library.beforeRows = beforeTurn
        tv.rig.library.afterSnapshot = repair
        tv.settings.beforeSave = { save() }
        tv.library.beforeApply = { cache() }

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))

        assertEquals(
            ChangeTiming(
                waitMs = 0,
                turnWaitMs = TURN_MS,
                recordMs = RECORD_MS,
                saveMs = SAVE_MS,
                cacheMs = CACHE_MS,
                totalMs = TURN_MS + RECORD_MS + SAVE_MS + CACHE_MS,
            ),
            tv.changeTimes.single(),
        )
    }

    @Test
    fun aChangeRefused_isReportedToo_withNoStepsTaken() {
        val tv = Tv(chosen = setOf(libA)).ready(setOf(libA))

        assertEquals(ChangeResult.LastLibrary, tv.change(emptySet()))

        assertEquals(ChangeTiming(0, 0, 0, 0, 0, 0), tv.changeTimes.single())
    }

    @Test
    fun theLine_isNumbersOnly() {
        val line =
            ChangeTiming(waitMs = 1, turnWaitMs = 2, recordMs = 3, saveMs = 4, cacheMs = 5, totalMs = 15).line()

        assertEquals("SelectionChangeTook wait=1 turnWait=2 record=3 save=4 cache=5 total=15", line)
    }

    private companion object {
        const val TURN_MS = 300L
        const val RECORD_MS = 400L
        const val SAVE_MS = 150L
        const val CACHE_MS = 250L
    }
}
