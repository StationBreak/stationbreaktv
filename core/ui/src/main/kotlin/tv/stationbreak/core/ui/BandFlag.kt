// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

private val DotSize = 6.dp
private val DotGap = 6.dp

/**
 * A number band's teletype flag (DESIGN.md "Band colours"): a small dot in the band's [accent], then the band [name] in
 * capitals and its range, "TOTS · 1-33"; [last] is null for the open-ended band, "GROWN-UPS · 100+". The text is
 * [textColor] in [style] (the flag style of the screen), never the accent: the accent is a shape. Not focusable and it
 * sets no semantics: the caller decides how a screen reader hears it, so the name is said once.
 */
@Suppress("LongParameterList") // One band's name and range, its two colours, the flag style and a modifier.
@Composable
fun BandFlag(
    name: String,
    first: Int,
    last: Int?,
    accent: Color,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val range =
        if (last == null) {
            stringResource(R.string.band_range_open, first)
        } else {
            stringResource(R.string.band_range, first, last)
        }
    val flag = stringResource(R.string.band_flag, name.uppercase(LocalConfiguration.current.locales[0]), range)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(DotSize).background(accent, CircleShape))
        Spacer(Modifier.width(DotGap))
        Text(flag, style = style, color = textColor, maxLines = 1)
    }
}
