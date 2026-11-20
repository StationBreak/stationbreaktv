// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val KeyHeight = 16.dp // a minimum: the cap grows with the font
private val KeyStroke = 1.dp
private val KeyRadius = 3.dp
private val KeyPadding = 6.dp
private val KeyRoundPadding = 4.dp
private val HintActionGap = 6.dp
private const val HINT_SP = 10

/**
 * A key cap (DESIGN.md "Key Hints"): the key's name in a 1 dp outline ([GuideColors.keyCapStroke]) with 3 dp corners,
 * 16 dp tall at least, in 10 sp SemiBold. OK is the one [round] cap, a circle. The guide family's colours, from
 * [LocalGuideColors].
 */
@Composable
fun KeyCap(
    label: String,
    round: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGuideColors.current
    val shape = if (round) CircleShape else RoundedCornerShape(KeyRadius)
    Text(
        text = label,
        modifier =
            modifier
                .heightIn(min = KeyHeight)
                .border(KeyStroke, colors.keyCapStroke, shape)
                .padding(horizontal = if (round) KeyRoundPadding else KeyPadding)
                .wrapContentHeight(Alignment.CenterVertically),
        style = guideText(HINT_SP.sp, FontWeight.SemiBold),
        color = colors.textPrimary,
        maxLines = 1,
    )
}

/**
 * One pair of a hint row (DESIGN.md "Key Hints"): the [key]'s cap, then what it does in 10 sp, in the quieter text
 * colour. A hint row keeps or drops a pair whole.
 */
@Composable
fun KeyHint(
    key: String,
    action: String,
    round: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KeyCap(key, round)
        Text(
            text = action,
            modifier = Modifier.padding(start = HintActionGap),
            style = guideText(HINT_SP.sp, FontWeight.Normal),
            color = LocalGuideColors.current.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
