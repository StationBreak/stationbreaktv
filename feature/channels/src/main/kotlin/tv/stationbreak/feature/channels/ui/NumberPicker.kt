// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.FocusStyle

private const val DIGITS = 3
private const val BASE = 10
private const val MAX_NUMBER = 999

/** The picker's three digits and which one the D-pad is on. Nothing here is Android, so it can be tested on the JVM. */
internal data class PickerState(
    val digits: List<Int>,
    val at: Int = 0,
) {
    /** The channel number the digits make. */
    val value: Int get() = digits.fold(0) { sum, d -> sum * BASE + d }

    /** The focused digit one more, 9 wrapping to 0. */
    fun up(): PickerState = step(1)

    /** The focused digit one less, 0 wrapping to 9. */
    fun down(): PickerState = step(BASE - 1)

    fun left(): PickerState = copy(at = (at - 1).coerceAtLeast(0))

    fun right(): PickerState = copy(at = (at + 1).coerceAtMost(DIGITS - 1))

    private fun step(by: Int): PickerState {
        val moved = digits.mapIndexed { i, d -> if (i == at) (d + by) % BASE else d }
        return copy(digits = moved)
    }

    companion object {
        /** A picker that shows [start], zero-padded to three digits, on its first digit. */
        fun of(start: Int): PickerState {
            val n = start.coerceIn(0, MAX_NUMBER)
            return PickerState(listOf(n / (BASE * BASE), n / BASE % BASE, n % BASE))
        }
    }
}

/** The three digits side by side; the focused one is drawn with the shared focus treatment. */
@Composable
internal fun NumberPicker(
    state: PickerState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.testTag(ChannelsTags.PICKER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.digits.forEachIndexed { i, digit ->
            val focused = i == state.at
            Box(
                Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .then(FocusStyle.drawn(focused))
                    .testTag(ChannelsTags.digit(i))
                    .semantics { selected = focused },
                contentAlignment = Alignment.Center,
            ) {
                Text(digit.toString(), Modifier.padding(4.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
