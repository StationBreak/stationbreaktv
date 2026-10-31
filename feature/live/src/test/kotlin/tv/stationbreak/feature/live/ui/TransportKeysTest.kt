// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.SeekDirection

/** The remote's transport keys (ADR-0021, 2): play/pause, and stepping back and ahead, held or tapped. */
class TransportKeysTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()
    private val paused = OverlayUp(paused = true)

    private fun down(
        key: Key,
        up: OverlayUp = OverlayUp.None,
        repeat: Int = 0,
        atMs: Long? = null,
    ) = handleLiveKey(KeyEventType.KeyDown, key, up, held, repeat, atMs = atMs) { sent += it }

    private fun release(
        key: Key,
        up: OverlayUp = OverlayUp.None,
    ) = handleLiveKey(KeyEventType.KeyUp, key, up, held) { sent += it }

    private val back = LiveCommand.Seek(SeekDirection.Back, atMs = null)
    private val ahead = LiveCommand.Seek(SeekDirection.Ahead, atMs = null)

    @Test
    fun playPauseSendsPlayPause() {
        assertTrue(down(Key.MediaPlayPause))
        assertTrue(release(Key.MediaPlayPause))
        assertEquals(listOf<LiveCommand>(LiveCommand.PlayPause), sent)
    }

    @Test
    fun mediaPlayAndPauseAreExplicit() {
        down(Key.MediaPlay)
        release(Key.MediaPlay)
        down(Key.MediaPause)
        release(Key.MediaPause)
        assertEquals(listOf(LiveCommand.Play, LiveCommand.Pause), sent)
    }

    @Test
    fun rewindPreviousAndSkipBackwardStepBack() {
        for (key in listOf(Key.MediaRewind, Key.MediaPrevious, Key.MediaSkipBackward)) {
            assertTrue(down(key, atMs = 5L))
            release(key)
        }
        val seek = LiveCommand.Seek(SeekDirection.Back, atMs = 5L)
        val released = LiveCommand.SeekReleased
        assertEquals(listOf(seek, released, seek, released, seek, released), sent)
    }

    @Test
    fun fastForwardNextAndSkipForwardStepAhead() {
        for (key in listOf(Key.MediaFastForward, Key.MediaNext, Key.MediaSkipForward)) {
            assertTrue(down(key, atMs = 7L))
            release(key)
        }
        val seek = LiveCommand.Seek(SeekDirection.Ahead, atMs = 7L)
        val released = LiveCommand.SeekReleased
        assertEquals(listOf(seek, released, seek, released, seek, released), sent)
    }

    @Test
    fun aHeldRewindSendsRepeatsThenOneRelease() {
        down(Key.MediaRewind, atMs = 100L)
        assertTrue(down(Key.MediaRewind, repeat = 1, atMs = 200L))
        assertTrue(down(Key.MediaRewind, repeat = 2, atMs = 300L))
        assertTrue(release(Key.MediaRewind))
        assertEquals(
            listOf(
                LiveCommand.Seek(SeekDirection.Back, atMs = 100L),
                LiveCommand.Seek(SeekDirection.Back, repeat = true, atMs = 200L),
                LiveCommand.Seek(SeekDirection.Back, repeat = true, atMs = 300L),
                LiveCommand.SeekReleased,
            ),
            sent,
        )
    }

    @Test
    fun theReleaseCountsOnlyOnceTheLastSeekKeyIsUp() {
        down(Key.MediaRewind)
        down(Key.MediaPrevious)
        release(Key.MediaRewind)
        assertEquals(listOf<LiveCommand>(back, back), sent)
        release(Key.MediaPrevious)
        assertEquals(LiveCommand.SeekReleased, sent.last())
        assertEquals(3, sent.size)
    }

    @Test
    fun leftAndRightScrubOnlyWhilePaused() {
        assertFalse(down(Key.DirectionLeft))
        assertFalse(down(Key.DirectionRight))
        assertFalse(release(Key.DirectionRight))
        assertEquals(emptyList<LiveCommand>(), sent)

        assertTrue(down(Key.DirectionLeft, paused))
        assertTrue(release(Key.DirectionLeft, paused))
        assertTrue(down(Key.DirectionRight, paused))
        assertTrue(release(Key.DirectionRight, paused))
        assertEquals(
            listOf(back, LiveCommand.SeekReleased, ahead, LiveCommand.SeekReleased),
            sent,
        )
    }

    @Test
    fun selectPlaysWhilePausedAndShowsTheBannerOtherwise() {
        assertTrue(down(Key.DirectionCenter, paused))
        assertTrue(release(Key.DirectionCenter, paused))
        assertTrue(down(Key.Enter, paused))
        assertTrue(release(Key.Enter, paused))
        assertTrue(down(Key.DirectionCenter))
        assertEquals(listOf(LiveCommand.Play, LiveCommand.Play, LiveCommand.ShowBanner), sent)
    }

    @Test
    fun overTheActionsBannerLeftAndRightStillMoveTheButtons() {
        val over = OverlayUp(actions = true, paused = true)
        assertFalse(down(Key.DirectionLeft, over))
        assertFalse(down(Key.DirectionRight, over))
        assertFalse(down(Key.DirectionCenter, over))
        // Only the "viewer is at work" nudge keeps the banner up; no seek and no play.
        assertTrue(sent.none { it is LiveCommand.Seek || it == LiveCommand.Play })
    }

    @Test
    fun overTheInfoOverlayLeftAndRightAreNotScrubs() {
        val over = OverlayUp(info = true, paused = true)
        assertFalse(down(Key.DirectionLeft, over))
        assertFalse(down(Key.DirectionCenter, over))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun nothingWithTheGuideOpen() {
        val guide = OverlayUp(guide = true, paused = true)
        val keys =
            listOf(
                Key.MediaPlayPause,
                Key.MediaPlay,
                Key.MediaPause,
                Key.MediaRewind,
                Key.MediaFastForward,
                Key.MediaPrevious,
                Key.MediaNext,
                Key.MediaSkipBackward,
                Key.MediaSkipForward,
                Key.DirectionLeft,
                Key.DirectionRight,
                Key.DirectionCenter,
            )
        for (key in keys) assertFalse("$key", down(key, guide))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun overlayUpOfCarriesThePausedFlag() {
        val view = pausedView()
        assertTrue(overlayUpOf(LiveState.Ident(null), null, false, view).paused)
        assertFalse(overlayUpOf(LiveState.Ident(null), null, false, view.copy(paused = false)).paused)
        assertFalse(overlayUpOf(LiveState.Ident(null), null, false, null).paused)
        assertFalse(overlayUpOf(LiveState.Ident(null), null, false).paused)
    }

    @Test
    fun transportKeysAreLogged() {
        val line =
            keyLogLine(KeyEventType.KeyDown, Key.MediaRewind, 89, 0, true, LiveState.Ident(null))
        assertNotNull(line)
        assertNull(keyLogLine(KeyEventType.KeyUp, Key.MediaRewind, 89, 0, true, LiveState.Ident(null)))
    }

    private fun pausedView() =
        tv.stationbreak.feature.live.TransportView(
            paused = true,
            seeking = false,
            seekable = true,
            visible = true,
            mediaMs = 0,
            targetMs = null,
            asOfMs = 0,
            blockStartMs = 0,
            inPointMs = 0,
            cutMs = 0,
            driftMs = 0,
        )
}
