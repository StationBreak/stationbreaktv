// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** How much of a dimmed (unavailable) row shows through, as the channel screens dim a row. */
private const val DIMMED_ALPHA = 0.7f

/**
 * The Cap row (#1151, #1152): a label, then every option of a segmented choice side by side with the [selected] one
 * filled (none when [selected] is null). It is one line and one focus stop: the screen owns the focus and the keys
 * (Left and Right move [selected]; this row only draws), so it is [focused] by the screen's say-so, drawn with the
 * panel's ring as the other rows are. While [enabled] is false it is dimmed and says so to a screen reader, and the
 * screen skips it in its focus order. [options] are the words as the screen words them (all of them stay visible).
 */
@Composable
@Suppress("LongParameterList") // The row's words, state and focus, and its tag, as the screens' own lines have.
fun CapRow(
    label: String,
    options: List<String>,
    selected: Int?,
    focused: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val panel = LocalPanelColors.current
    val dim = if (enabled) 1f else DIMMED_ALPHA
    val text = panel.text.copy(alpha = panel.text.alpha * dim).compositeOver(panel.ground)
    Row(
        modifier
            .fillMaxWidth()
            .panelFocus(focused && enabled)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = focused && enabled
                selected?.let { stateDescription = options[it] }
                if (!enabled) disabled()
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = text)
        options.forEachIndexed { i, option ->
            val on = i == selected
            Text(
                option,
                Modifier
                    .background(if (on) text else panel.ground, RoundedCornerShape(SEGMENT_RADIUS))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (on) panel.ground else text,
            )
        }
    }
}

private val SEGMENT_RADIUS = 6.dp
