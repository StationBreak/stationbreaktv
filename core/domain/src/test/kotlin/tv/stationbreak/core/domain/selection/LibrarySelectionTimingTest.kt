// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import tv.stationbreak.core.domain.lineup.repair.RepairPhases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

        val timing = tv.changeTimes.single()
        assertEquals(
            ChangeTiming(
                waitMs = 0,
                turnWaitMs = TURN_MS,
                recordMs = RECORD_MS,
                saveMs = SAVE_MS,
                cacheMs = CACHE_MS,
                totalMs = TURN_MS + RECORD_MS + SAVE_MS + CACHE_MS,
            ),
            timing.copy(repair = RepairPhases.NONE),
        )
        // The repair's own steps are reported too, on the same clock: the slow snapshot read is the current days'
        // (a build) or the new selection's, and no other step took any time.
        val phases = timing.repair
        assertNotEquals(RepairPhases.NONE, phases)
        assertEquals(RECORD_MS, phases.daysMs + phases.snapsMs)
        assertEquals(0, phases.admittedMs + phases.keptMs + phases.layoutMs + phases.pinMs + phases.writeMs)
    }

    @Test
    fun aChangeRefused_isReportedToo_withNoStepsTaken() {
        val tv = Tv(chosen = setOf(libA)).ready(setOf(libA))

        assertEquals(ChangeResult.LastLibrary, tv.change(emptySet()))

        assertEquals(ChangeTiming(0, 0, 0, 0, 0, 0), tv.changeTimes.single())
    }

    @Test
    fun theLine_isNumbersOnly() {
        val phases =
            RepairPhases(
                daysMs = 6,
                snapsMs = 7,
                admittedMs = 8,
                beforeMs = 9,
                keptMs = 10,
                poolsMs = 11,
                liveMs = 12,
                layoutMs = 13,
                pinMs = 14,
                writeMs = 15,
                cpuMs = 16,
                gcCount = 17,
                gcMs = 18,
            )
        val line =
            ChangeTiming(waitMs = 1, turnWaitMs = 2, recordMs = 3, saveMs = 4, cacheMs = 5, totalMs = 15, phases)
                .line()

        assertEquals(
            "SelectionChangeTook wait=1 turnWait=2 record=3 save=4 cache=5 total=15 days=6 snaps=7 admitted=8 " +
                "before=9 kept=10 pools=11 live=12 layout=13 pin=14 write=15 cpu=16 gc=17 gcMs=18",
            line,
        )
    }

    @Test
    fun aChangeWithNoRepair_saysSo_withMinusOnes() {
        val line = ChangeTiming(0, 0, 0, 0, 0, 0).line()

        assertEquals(
            "SelectionChangeTook wait=0 turnWait=0 record=0 save=0 cache=0 total=0 days=-1 snaps=-1 admitted=-1 " +
                "before=-1 kept=-1 pools=-1 live=-1 layout=-1 pin=-1 write=-1 cpu=-1 gc=-1 gcMs=-1",
            line,
        )
    }

    private companion object {
        const val TURN_MS = 300L
        const val RECORD_MS = 400L
        const val SAVE_MS = 150L
        const val CACHE_MS = 250L
    }
}
