// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.channels.builder.ui.takenWhileTyping

class BuilderKeysTest {
    @Test
    fun whileTypingBackUpAndDownAreTakenSoTheFocusStaysOnTheField() {
        assertTrue(takenWhileTyping(Key.Back))
        assertTrue(takenWhileTyping(Key.DirectionUp))
        assertTrue(takenWhileTyping(Key.DirectionDown))
    }

    @Test
    fun theKeysThatTypeAreTheFields() {
        assertFalse(takenWhileTyping(Key.A))
        assertFalse(takenWhileTyping(Key.DirectionLeft))
        assertFalse(takenWhileTyping(Key.Enter))
    }
}
