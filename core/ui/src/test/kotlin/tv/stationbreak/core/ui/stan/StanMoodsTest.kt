// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(hostFrame(StanMood.Pleased, 0.26, talking = false).y < rest - 5)
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
    fun talkingFlapsTheMouth() {
        val flap = setOf(Mouth.Smile, Mouth.O)
        for (mood in TALKERS) {
            val a = hostState(mood, 0.0, talking = true).face.mouth
            val b = hostState(mood, 0.09, talking = true).face.mouth
            assertNotEquals("$mood", a, b)
            assertTrue("$mood", a in flap && b in flap)
        }
        for (mood in StanMood.entries) {
            val own = hostState(mood, 0.09, talking = false).face.mouth
            val still = hostState(mood, 0.0, talking = false).face.mouth
            assertEquals("$mood", still, own)
        }
        assertEquals(Mouth.Flat, hostState(StanMood.Shrug, 0.09, talking = false).face.mouth)
    }

    @Test
    fun busyAndDozingKeepTheirOwnMouthWhileALineIsUp() {
        // He is concentrating on the aerial, or asleep: the line is his, the mouth stays put (as in the approved mock).
        assertEquals(Mouth.Tongue, hostState(StanMood.Busy, 0.09, talking = true).face.mouth)
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
                var i = 0
                while (i <= 200) {
                    val t = i * 0.05
                    val f = hostFrame(mood, t, talking)
                    val at = "$mood t=$t"
                    assertTrue(at, f.x.isFinite() && f.y.isFinite() && f.sc.isFinite())
                    assertTrue(at, f.x > 0 && f.x < HOST_W)
                    assertTrue(at, f.y > 0 && f.y < HOST_H)
                    i++
                }
            }
        }
    }

    private companion object {
        val TALKERS = listOf(StanMood.Idle, StanMood.Pleased, StanMood.Shrug, StanMood.Cheer)
    }
}
