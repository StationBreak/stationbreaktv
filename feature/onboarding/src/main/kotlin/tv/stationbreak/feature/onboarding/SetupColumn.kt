// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tv.stationbreak.feature.onboarding.server.Gap

private val ContentWidth = 640.dp

/**
 * The column every setup screen puts its content in, inside a [SetupScaffold]: [contentPadding] (the scaffold's safe
 * area) with its top at [topOffset] of the screen's height, a fixed height so the headline does not move when a
 * result appears below it, and a content column at most [ContentWidth] wide, centred, with [Gap] between its children.
 * The padding is inside the scroll area, which scrolls when a large font or a long error does not fit, unless
 * [scrollable] is off. [modifier] goes on the outer box (a screen's test tag); [contentModifier] on the content column.
 */
@Suppress("LongParameterList") // A padding, an offset, two modifiers and a switch, each defaulted but the first two.
@Composable
internal fun SetupColumn(
    contentPadding: PaddingValues,
    topOffset: Float,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrolling = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(modifier)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(scrolling)
                    .padding(contentPadding.withTop(maxHeight * topOffset)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth().then(contentModifier),
                verticalArrangement = Arrangement.spacedBy(Gap),
                content = content,
            )
        }
    }
}
