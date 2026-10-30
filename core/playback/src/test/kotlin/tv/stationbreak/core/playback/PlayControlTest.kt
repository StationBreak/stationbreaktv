// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PlayControlTest {
    private val player = FakeVideoPlayer()

    @Test
    fun controlsOfTheSamePlayerAndGenerationAreEqual() {
        assertEquals(GenerationControl(player, 3), GenerationControl(player, 3))
        assertEquals(GenerationControl(player, 3).hashCode(), GenerationControl(player, 3).hashCode())
    }

    @Test
    fun controlsOfAnotherGenerationOrPlayerDiffer() {
        assertNotEquals(GenerationControl(player, 3), GenerationControl(player, 4))
        assertNotEquals(GenerationControl(player, 3), GenerationControl(FakeVideoPlayer(), 3))
    }

    @Test
    fun positionIsTheLivePositionOfItsGeneration() =
        runTest {
            player.live = 7.seconds
            assertEquals(7.seconds, GenerationControl(player, 2).position())
            assertEquals("live:2", player.calls.last())
        }

    @Test
    fun noPlayHasNoPosition() =
        runTest {
            assertNull(PlayControl.None.position())
        }
}
