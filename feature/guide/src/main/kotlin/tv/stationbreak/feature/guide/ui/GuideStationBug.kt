// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

private val BugIcon = 16.dp
private val BugIconGap = 6.dp
private val BugGap = 10.dp
private val BugTextSize = 13.sp
private val BugLineHeight = 16.sp

/**
 * The station bug that leads the panel's top line: Stan's head and "Station Break" in Ranchers (DESIGN.md, Marks), a
 * quiet broadcaster logo. It keeps its size (the channel line beside it is what ellipsizes) and is decoration: its own
 * node, cleared of semantics so the panel's live region never reads it out, found by [GuideTags.STATION_BUG].
 */
@Composable
internal fun StationBug() {
    val colors = LocalGuideColors.current
    // Light is the theme that tints the channel marks (markTint), so it is also the one that takes the paper icon.
    val icon = if (colors.markTint != null) UiR.drawable.stan_icon_paper else UiR.drawable.stan_icon_ink
    Row(
        Modifier.clearAndSetSemantics { testTag = GuideTags.STATION_BUG },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(BugIcon),
        )
        Text(
            text = stringResource(R.string.guide_station_bug),
            modifier = Modifier.padding(start = BugIconGap, end = BugGap),
            style =
                guideText(BugTextSize, FontWeight.Normal).copy(
                    fontFamily = BroadcastType.Voice,
                    lineHeight = BugLineHeight,
                ),
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}
