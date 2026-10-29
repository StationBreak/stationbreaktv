// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.feature.guide.GuideDirection
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@RunWith(AndroidJUnit4::class)
class GuideKeyTest {
    private val moves = mutableListOf<Triple<GuideDirection, Boolean, Long>>()

    private fun send(
        code: Int,
        action: Int = ACTION_DOWN,
        repeatCount: Int = 0,
        eventTime: Long = 1_000,
    ) = guideKey(
        ComposeKeyEvent(NativeKeyEvent(500, eventTime, action, code, repeatCount)),
        move = { direction, repeat, atMs -> moves += Triple(direction, repeat, atMs) },
        select = {},
    )

    @Test
    fun aPressPassesNoRepeatAndItsOwnEventTime() {
        send(KEYCODE_DPAD_DOWN, repeatCount = 0, eventTime = 1_234)
        assertEquals(listOf(Triple(GuideDirection.Down, false, 1_234L)), moves)
    }

    @Test
    fun aRepeatPassesRepeatAndItsOwnEventTime() {
        send(KEYCODE_DPAD_UP, repeatCount = 3, eventTime = 5_678)
        assertEquals(listOf(Triple(GuideDirection.Up, true, 5_678L)), moves)
    }

    @Test
    fun aReleaseMovesNothing() {
        send(KEYCODE_DPAD_DOWN, action = ACTION_UP, eventTime = 9_999)
        assertEquals(emptyList<Triple<GuideDirection, Boolean, Long>>(), moves)
    }
}
