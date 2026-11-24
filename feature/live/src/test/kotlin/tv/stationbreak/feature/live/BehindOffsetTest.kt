// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.feature.live.ui.OffsetParts
import tv.stationbreak.feature.live.ui.behindOffset

/**
 * The offset the transport banner shows after scrub steps, read from the real state the sitting publishes: each Back
 * step adds `stepBack` (10 s) and each Ahead step takes off `stepAhead` (30 s), paused or playing, whatever the clock
 * does between presses.
 */
class BehindOffsetTest {
    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        /** The banner's offset now, as the strip computes it from the published view. */
        fun shown(): OffsetParts? = behindOffset(checkNotNull(r.live.transport.value), r.nowMs())

        fun shownMs(): Long = checkNotNull(r.live.transport.value).offsetMs(r.nowMs())
    }

    private suspend fun TestScope.keys(): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private fun back(
        repeat: Boolean = false,
        atMs: Long? = null,
    ) = LiveCommand.Seek(SeekDirection.Back, repeat, atMs)

    private fun ahead(atMs: Long? = null) = LiveCommand.Seek(SeekDirection.Ahead, false, atMs)

    @Test
    fun eachBackStepAddsTenSecondsWhilePlaying() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            for (n in 1..8) {
                k.r.at(MIN + n * 100L)
                k.press(back(atMs = MIN + n * 100L))
                assertEquals("after $n steps", n * 10_000L, k.shownMs())
            }
            assertEquals(OffsetParts(0, 1, 20), k.shown())
        }

    @Test
    fun eachBackStepAddsTenSecondsWhilePausedEvenWithTheClockRunningBetweenPresses() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            // Paused 2 s: the picture is that far behind live before the first step, which adds exactly 10 s to it.
            k.r.at(MIN + 2_000)
            k.press(back(atMs = MIN + 2_000))
            assertEquals(12_000L, k.shownMs())
            for (n in 2..4) {
                k.r.at(MIN + 2_000 + n * 100L)
                k.press(back(atMs = MIN + 2_000 + n * 100L))
                assertEquals("after $n steps", 12_000L + (n - 1) * 10_000L, k.shownMs())
            }
            assertEquals(OffsetParts(0, 0, 42), k.shown())
        }

    @Test
    fun eachAheadStepTakesOffThirtySecondsWhilePlaying() = runTest { aheadSteps(paused = false) }

    @Test
    fun eachAheadStepTakesOffThirtySecondsWhilePaused() = runTest { aheadSteps(paused = true) }

    private suspend fun TestScope.aheadSteps(paused: Boolean) {
        val k = keys()
        k.r.atMin(1)
        if (paused) k.press(LiveCommand.PlayPause)
        // Twelve back steps: two minutes behind (paused: the picture was paused at once, so no time has gone by).
        for (n in 1..12) {
            k.r.at(MIN + n * 100L)
            k.press(back(atMs = MIN + n * 100L))
        }
        val base = k.shownMs()
        for (n in 1..3) {
            k.r.at(MIN + 1_200 + n * 100L)
            k.press(ahead(atMs = MIN + 1_200 + n * 100L))
            assertEquals("paused=$paused after $n ahead steps", base - n * 30_000L, k.shownMs())
        }
        assertEquals(base - 90_000L, k.shownMs())
        assertEquals(OffsetParts.of(base - 90_000L), k.shown())
    }

    @Test
    fun aHeldKeysRepeatsStepAtTheSameSize() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(back(atMs = MIN))
            assertEquals(10_000L, k.shownMs())
            var shown = 10_000L
            var steps = 0
            // The held pace is the flip's; each repeat that steps adds exactly one step.
            for (i in 1..40) {
                val at = MIN + i * 100L
                k.r.at(at)
                k.press(back(repeat = true, atMs = at))
                val now = k.shownMs()
                if (now != shown) {
                    assertEquals(shown + 10_000L, now)
                    shown = now
                    steps++
                }
            }
            assertEquals(true, steps > 0)
        }
}
