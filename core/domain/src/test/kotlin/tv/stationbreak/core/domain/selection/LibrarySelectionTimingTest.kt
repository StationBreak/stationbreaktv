// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.repair.RepairPhases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
        // (a build, before the turn or in it) or the new selection's (a lane the main path waits for), and no other
        // step took any time.
        val phases = timing.repair
        assertNotEquals(RepairPhases.NONE, phases)
        assertEquals(
            RECORD_MS,
            listOf(phases.warmMs, phases.daysMs, phases.lanesWaitMs).filter { it > 0 }.sum(),
            "the slow read is a wait for the walk lane: $phases",
        )
        val others = listOf(phases.keptMs, phases.layoutMs, phases.pinMs, phases.writeMs)
        assertTrue(others.all { it <= 0 }, "no other step took any time: $others")
    }

    @Test
    fun theRepairsSteps_addUpToItsRecordTime_eachCountedOnce() {
        val tv = Tv().ready(setOf(libA, libB))
        // Time passes in many steps, some inside others: each snapshot read (in the days' build and in the walk lane),
        // each current-row read (in the kept windows, the early read's lane and the tail pools) and the save, which is
        // not the repair's. The lanes' own times overlap the main path, so they are in no sum: the main path's steps
        // and its waits for the lanes add up to the record.
        tv.rig.library.afterSnapshot = { tv.ticks += SNAPSHOT_MS.milliseconds }
        tv.rig.library.beforeCurrentIds = { tv.ticks += LIVE_MS.milliseconds }
        tv.settings.beforeSave = { tv.ticks += SAVE_MS.milliseconds }

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))

        val timing = tv.changeTimes.single()
        val p = timing.repair
        val steps =
            listOf(
                p.warmMs,
                p.daysMs,
                p.keptMs,
                p.poolsMs,
                p.layoutMs,
                p.pinMs,
                p.writeMs,
                p.otherMs,
                p.lanesWaitMs,
            )
        assertTrue(p.liveMs > 0 && p.snapsMs > 0, "time passed inside the lanes: $p")
        assertEquals(timing.recordMs, steps.filter { it > 0 }.sum(), "every millisecond counted once: $p")
    }

    @Test
    fun anUntickRightAfterAnAdd_buildsTodaysDaysBeforeTheTurn_notInIt() {
        val tv = Tv(chosen = setOf(libA, libC)).ready(setOf(libA, libB, libC))
        tv.rig.days()
        // The add's save moves the saved selection, and today's days under it are not built yet (the add's own steps
        // may build them; here they are let go, as after a lineup refresh or with the add pass still to run).
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))
        tv.rig.source.forget(ACCOUNT)
        tv.rig.source.evictSnapshotsNaming(ACCOUNT, libA)
        // Every snapshot read is slow, so a day build shows in the step that ran it.
        tv.rig.library.afterSnapshot = { tv.ticks += SNAPSHOT_MS.milliseconds }

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))

        val phases = tv.changeTimes.last().repair
        assertEquals(0, phases.daysMs, "the turn built no day")
        assertTrue(phases.warmMs >= SNAPSHOT_MS, "the days were built before the turn: $phases")
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
                warmMs = 5,
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
                otherMs = 19,
                cpuMs = 16,
                gcCount = 17,
                gcMs = 18,
                walkTxMs = 20,
                lanesWaitMs = 21,
                buildCpuMs = 22,
            )
        val line =
            ChangeTiming(waitMs = 1, turnWaitMs = 2, recordMs = 3, saveMs = 4, cacheMs = 5, totalMs = 15, phases)
                .line()

        assertEquals(
            "SelectionChangeTook wait=1 turnWait=2 record=3 save=4 cache=5 total=15 warm=5 days=6 snaps=7 " +
                "admitted=8 before=9 kept=10 pools=11 live=12 layout=13 pin=14 write=15 other=19 cpu=16 gc=17 " +
                "gcMs=18 walkTxMs=20 lanesWaitMs=21 buildCpuMs=22",
            line,
        )
    }

    @Test
    fun aChangeWithNoRepair_saysSo_withMinusOnes() {
        val line = ChangeTiming(0, 0, 0, 0, 0, 0).line()

        assertEquals(
            "SelectionChangeTook wait=0 turnWait=0 record=0 save=0 cache=0 total=0 warm=-1 days=-1 snaps=-1 " +
                "admitted=-1 before=-1 kept=-1 pools=-1 live=-1 layout=-1 pin=-1 write=-1 other=-1 cpu=-1 gc=-1 " +
                "gcMs=-1 walkTxMs=-1 lanesWaitMs=-1 buildCpuMs=-1",
            line,
        )
    }

    @Test
    fun aSaveWithNoLineup_logsTheSamePhasesAsAnAdd_notAWarmNumberAmongMinusOnes() {
        val steps = ChangeSteps(TimeSource.Monotonic)
        // A plain save still warms today's days first (the allocation cannot know there is no lineup); no repair runs.
        runBlocking { steps.warming {} }

        val line = steps.timing().line()

        assertTrue(line.endsWith(" " + RepairPhases.NONE.line()), line)
        assertTrue("warm=-1 " in line, line)
    }

    private companion object {
        const val TURN_MS = 300L
        const val RECORD_MS = 400L
        const val SAVE_MS = 150L
        const val CACHE_MS = 250L
        const val SNAPSHOT_MS = 100L
        const val LIVE_MS = 7L
    }
}
