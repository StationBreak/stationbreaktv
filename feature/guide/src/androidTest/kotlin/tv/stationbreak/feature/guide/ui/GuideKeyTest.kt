// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_MENU
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.feature.guide.GuideDirection
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@RunWith(AndroidJUnit4::class)
class GuideKeyTest {
    private val moves = mutableListOf<Triple<GuideDirection, Boolean, Long>>()
    private var menus = 0

    private fun send(
        code: Int,
        action: Int = ACTION_DOWN,
        repeatCount: Int = 0,
        eventTime: Long = 1_000,
    ) = guideKey(
        ComposeKeyEvent(NativeKeyEvent(500, eventTime, action, code, repeatCount)),
        move = { direction, repeat, atMs -> moves += Triple(direction, repeat, atMs) },
        select = {},
        menu = { menus++ },
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

    @Test
    fun theMenuKeyTogglesTheSheetOncePerPressAndIsConsumedEitherWay() {
        assertTrue(send(KEYCODE_MENU))
        assertEquals(1, menus)
        // Auto-repeat and the release do not toggle it again, but are the guide's all the same.
        assertTrue(send(KEYCODE_MENU, repeatCount = 1))
        assertTrue(send(KEYCODE_MENU, action = ACTION_UP))
        assertEquals(1, menus)
        assertEquals(emptyList<Triple<GuideDirection, Boolean, Long>>(), moves)
    }

    @Test
    fun theMenuKeyIsNotTheGuidesWhenThereIsNoSheetToToggle() {
        val handled =
            guideKey(
                ComposeKeyEvent(NativeKeyEvent(500, 1_000, ACTION_DOWN, KEYCODE_MENU, 0)),
                move = { _, _, _ -> },
                select = {},
            )
        assertFalse(handled)
    }
}
