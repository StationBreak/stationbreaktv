// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a key does under a graph cover (the opening, setup's cheer): only a fresh press acts, a repeat or a key held
 * from before never lifts it, Back acts on the release of a press the cover saw, volume passes, and a key a cover
 * consumed is swallowed to its end after the cover has gone.
 */
class GraphCoverKeysTest {
    private class Recording : GraphCover {
        var keys = 0
        var backs = 0
        override val active = true
        override val holdsFocus = false

        override fun onKey() {
            keys++
        }

        override fun onBack() {
            backs++
        }
    }

    private val cover = Recording()
    private val swallowing = SwallowedKeys()

    private fun down(
        key: Key,
        repeat: Int = 0,
        under: GraphCover? = cover,
    ) = handleCoverKey(key, KeyEventType.KeyDown, repeat, under, swallowing)

    private fun up(
        key: Key,
        under: GraphCover? = cover,
    ) = handleCoverKey(key, KeyEventType.KeyUp, 0, under, swallowing)

    @Test
    fun aFreshPressActsOnceAndItsRepeatsAndReleaseAreConsumed() {
        assertTrue(down(Key.DirectionCenter))
        assertTrue(down(Key.DirectionCenter, repeat = 1))
        assertTrue(down(Key.DirectionCenter, repeat = 2))
        assertTrue(up(Key.DirectionCenter))

        assertEquals(1, cover.keys)
    }

    @Test
    fun aKeyHeldFromBeforeTheCoverNeverLiftsIt() {
        // Its first event under the cover is a repeat: a held OK from the cheer, say.
        assertTrue(down(Key.DirectionCenter, repeat = 5))
        assertTrue(up(Key.DirectionCenter))

        assertEquals(0, cover.keys)
    }

    @Test
    fun backActsOnTheReleaseOfAPressTheCoverSaw() {
        assertTrue(down(Key.Back))
        assertEquals("not on the press", 0, cover.backs)
        assertTrue(up(Key.Back))
        assertEquals(1, cover.backs)
        assertEquals("Back is no skip", 0, cover.keys)
    }

    @Test
    fun aBackHeldFromBeforeTheCoverDoesNothingOnRelease() {
        assertTrue(down(Key.Back, repeat = 3))
        assertTrue(up(Key.Back))

        assertEquals(0, cover.backs)
    }

    @Test
    fun aBackReleaseWithNoPressSeenDoesNothing() {
        assertTrue(up(Key.Back))

        assertEquals(0, cover.backs)
    }

    @Test
    fun volumePassesThrough() {
        assertFalse(down(Key.VolumeUp))
        assertFalse(up(Key.VolumeUp))
        assertEquals(0, cover.keys)
    }

    @Test
    fun aKeyThatLiftedTheCoverIsSwallowedToItsEndAfterIt() {
        assertTrue(down(Key.DirectionCenter))
        // The cover is gone: its repeats and release still never reach what is beneath.
        assertTrue(down(Key.DirectionCenter, repeat = 1, under = null))
        assertTrue(up(Key.DirectionCenter, under = null))
        assertFalse("then keys are the graph's again", down(Key.DirectionCenter, under = null))
    }
}
