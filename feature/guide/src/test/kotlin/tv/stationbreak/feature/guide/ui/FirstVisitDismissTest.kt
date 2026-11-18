// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which key events dismiss the first visit's note (#94). */
class FirstVisitDismissTest {
    @Test
    fun aFreshKeyDownDismisses() = assertTrue(dismissesFirstVisitNote(KeyEventType.KeyDown, 0))

    @Test
    fun anAutoRepeatDoesNot() = assertFalse(dismissesFirstVisitNote(KeyEventType.KeyDown, 3))

    @Test
    fun aKeyUpDoesNot() = assertFalse(dismissesFirstVisitNote(KeyEventType.KeyUp, 0))
}
