// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Stan's host moods: each is the pose it says, he talks when asked, and he never leaves the host's space. */
class StanMoodsTest {
    @Test
    fun idleStandsWithArmsAtRest() {
        val s = hostState(StanMood.Idle, 1.0, talking = false)
        assertEquals(Eyes.Open, s.face.eyes)
        assertEquals(Arms.Rest, s.arms)
    }

    @Test
    fun idleBlinks() {
        assertEquals(0.1, hostState(StanMood.Idle, 3.75, talking = false).face.open!!, 0.0)
        assertEquals(1.0, hostState(StanMood.Idle, 3.0, talking = false).face.open!!, 0.0)
    }

    @Test
    fun busyTunesTheAerial() {
        assertEquals(Arms.Tune, hostState(StanMood.Busy, 1.0, talking = false).arms)
    }

    @Test
    fun pleasedHasHappyEyes() {
        val s = hostState(StanMood.Pleased, 1.0, talking = false)
        assertEquals(Eyes.Happy, s.face.eyes)
        assertEquals(Mouth.Smile, s.face.mouth)
    }

    @Test
    fun pleasedHopsOnceAtTheStart() {
        val rest = hostFrame(StanMood.Pleased, 0.0, talking = false).y
        // The approved mock's hop: 14 units at its peak, a quarter of the way through sin(10.5 t)'s period.
        assertEquals(rest - 14, hostFrame(StanMood.Pleased, PI / 21, talking = false).y, 0.05)
        // One hop only: back on the ground well before the bob starts at 0.6 s.
        for (t in listOf(0.31, 0.45, 0.55, 0.6)) {
            assertEquals("t=$t", rest, hostFrame(StanMood.Pleased, t, talking = false).y, 0.0)
        }
    }

    @Test
    fun pleasedNeverJumps() {
        // 1 ms apart, Stan never moves more than the hop's steepest speed (147 units/s) allows, plus rounding.
        var previous = hostFrame(StanMood.Pleased, 0.0, talking = false).y
        for (ms in 1..1500) {
            val y = hostFrame(StanMood.Pleased, ms / 1000.0, talking = false).y
            assertTrue("t=${ms}ms", abs(y - previous) <= 0.3)
            previous = y
        }
    }

    @Test
    fun shrugShrugs() {
        val s = hostState(StanMood.Shrug, 1.0, talking = false)
        assertEquals(Arms.Shrug, s.arms)
        assertEquals(Ears.Droop, s.ears)
    }

    @Test
    fun cheerRaisesBothArms() {
        val s = hostState(StanMood.Cheer, 1.0, talking = false)
        assertEquals(Arms.Cheer, s.arms)
        assertEquals(Eyes.Happy, s.face.eyes)
    }

    @Test
    fun dozingSleeps() {
        val s = hostState(StanMood.Dozing, 1.0, talking = false)
        assertEquals(Eyes.Sleep, s.face.eyes)
        assertEquals(Arms.Rest, s.arms)
        assertEquals(false, s.bear)
    }

    @Test
    fun dozingBreathesAsOffTheAir() {
        for (t in listOf(0.0, 0.5, 1.0, 2.0, 3.3)) {
            assertEquals("t=$t", StanScene.OffAir.frame(t).sy, hostFrame(StanMood.Dozing, t, false).sy, 0.0)
            assertEquals("t=$t", asleep(sleepBreath(t), bear = false), hostState(StanMood.Dozing, t, false))
        }
        assertNotEquals(hostFrame(StanMood.Dozing, 0.0, false).sy, hostFrame(StanMood.Dozing, 1.0, false).sy)
    }

    @Test
    fun dozingAndBlankLinesShowNoBubble() {
        assertNull(hostLine(StanMood.Dozing, "WHAT'S ON TONIGHT?"))
        assertNull(hostLine(StanMood.Idle, ""))
        assertNull(hostLine(StanMood.Idle, "   "))
        assertNull(hostLine(StanMood.Idle, null))
        assertEquals("THERE IT IS!", hostLine(StanMood.Pleased, "THERE IT IS!"))
    }

    @Test
    fun talkingFlapsTheMouth() {
        val flap = setOf(Mouth.Smile, Mouth.O)
        for (mood in TALKERS) {
            val a = hostState(mood, 0.0, talking = true).face.mouth
            val b = hostState(mood, 0.10, talking = true).face.mouth
            assertNotEquals("$mood", a, b)
            assertTrue("$mood", a in flap && b in flap)
            // Every 0.1 s, two ticks at 20 fps: the tick in between keeps the mouth it had.
            assertEquals("$mood", a, hostState(mood, 0.05, talking = true).face.mouth)
        }
        for (mood in StanMood.entries) {
            val own = hostState(mood, 0.10, talking = false).face.mouth
            val still = hostState(mood, 0.0, talking = false).face.mouth
            assertEquals("$mood", still, own)
        }
        assertEquals(Mouth.Flat, hostState(StanMood.Shrug, 0.10, talking = false).face.mouth)
    }

    @Test
    fun busyAndDozingKeepTheirOwnMouthWhileALineIsUp() {
        // He is concentrating on the aerial, or asleep: the mouth stays put (as in the approved mock).
        assertEquals(Mouth.Tongue, hostState(StanMood.Busy, 0.10, talking = true).face.mouth)
        assertEquals(Mouth.O, hostState(StanMood.Dozing, 0.0, talking = true).face.mouth)
    }

    @Test
    fun hostFramesHaveNoGroundBlocksOrTitle() {
        for (mood in StanMood.entries) {
            val f = hostFrame(mood, 1.0, talking = false)
            assertEquals(StanBackground.None, f.bg)
            assertNull(f.blocks)
            assertNull(f.title)
            assertEquals(1.0, f.fade, 0.0)
        }
    }

    @Test
    fun everyMoodStaysInHostSpace() {
        for (mood in StanMood.entries) {
            for (talking in listOf(false, true)) {
                for (i in 0..200) {
                    val t = i * 0.05
                    val f = hostFrame(mood, t, talking)
                    val at = "$mood t=$t"
                    assertTrue(at, f.x.isFinite() && f.y.isFinite() && f.sc.isFinite() && f.sy.isFinite())
                    assertTrue(at, f.x > 0 && f.x < HOST_W)
                    assertTrue(at, f.y > 0 && f.y < HOST_H)
                    assertInsideHost(f, at)
                }
            }
        }
    }

    @Test
    fun cheerHandsAreUpAndInsideTheHost() {
        for (i in 0..200) {
            val f = hostFrame(StanMood.Cheer, i * 0.05, talking = false)
            val hands = f.stan.filterIsInstance<StanCircle>().filter { it.r == 12.0 && abs(abs(it.x) - 150) < 0.01 }
            assertEquals(2, hands.size)
            // Above the cabinet's top edge (-90): both arms are up. assertInsideHost covers where they land.
            assertTrue(hands.all { it.y < -90 })
            assertInsideHost(f, "Cheer t=${i * 0.05}")
        }
    }

    @Test
    fun dozingDrawsThreeZs() {
        for (i in 0..200) {
            val f = hostFrame(StanMood.Dozing, i * 0.05, talking = false)
            assertEquals(3, f.stan.filterIsInstance<StanGlyph>().count { it.ch == "Z" })
        }
    }

    /**
     * Every circle (hands, ear tips, the bobble) and glyph (the Zs) of [f], taken to host space through the figure's
     * transform (it has no rotation), lies inside the host. A glyph is checked from its baseline up by its size, and
     * across by 0.7 of its size (Ranchers' Z is narrower than that).
     */
    private fun assertInsideHost(
        f: StanFrame,
        at: String,
    ) {
        fun hx(x: Double) = f.x + f.sc * x

        fun hy(y: Double) = f.y + f.sc * f.sy * y

        for (shape in f.stan) {
            when (shape) {
                is StanCircle -> {
                    assertInHost("$at circle", hx(shape.x - shape.r), hy(shape.y - shape.r))
                    assertInHost("$at circle", hx(shape.x + shape.r), hy(shape.y + shape.r))
                }

                is StanGlyph -> {
                    assertInHost("$at glyph", hx(shape.x), hy(shape.y - shape.size))
                    assertInHost("$at glyph", hx(shape.x + GLYPH_WIDTH * shape.size), hy(shape.y))
                }

                else -> {}
            }
        }
    }

    private fun assertInHost(
        at: String,
        x: Double,
        y: Double,
    ) = assertTrue("$at at ($x, $y)", x in 0.0..HOST_W && y in 0.0..HOST_H)

    private companion object {
        const val GLYPH_WIDTH = .7
        val TALKERS = listOf(StanMood.Idle, StanMood.Pleased, StanMood.Shrug, StanMood.Cheer)
    }
}
