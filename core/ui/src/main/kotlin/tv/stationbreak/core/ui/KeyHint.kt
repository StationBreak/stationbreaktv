// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
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

/** The gap between two pairs of a hint row (DESIGN.md `hint-pair`). */
val HintPairGap = 20.dp

/** A hint row's height, at least: a key cap grows with the font inside it. */
val HintMinHeight = 20.dp

/** What goes between two pairs when a hint row is read out as one line. */
const val HINT_SPACE = "   "

/**
 * A key cap (DESIGN.md "Key Hints"): the key's name in a 1 dp outline ([PanelColors.keyCapStroke]) with 3 dp corners,
 * 16 dp tall at least, in 10 sp SemiBold. OK is the one [round] cap, a circle. Colours from [LocalPanelColors], which
 * the guide family provides.
 */
@Composable
fun KeyCap(
    label: String,
    round: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPanelColors.current
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
        color = colors.text,
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
            color = LocalPanelColors.current.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One pair of a [KeyHintRow]: the [key] as its cap shows it, what it does ([action]), whether the cap is the round
 * OK, and the key's name as a screen reader says it ([spokenKey]: words, where the cap shows arrows).
 */
data class KeyHintPair(
    val key: String,
    val action: String,
    val round: Boolean = false,
    val spokenKey: String = key,
)

/** How a hint row reads out: each pair's spoken key and action, the pairs [HINT_SPACE] apart. */
fun spokenHint(pairs: List<KeyHintPair>): String = pairs.joinToString(HINT_SPACE) { "${it.spokenKey} ${it.action}" }

/**
 * A sheet's hint row (DESIGN.md "Key Hints"): [pairs] with their key caps, [HintPairGap] apart, read out as one line
 * ([spokenHint]).
 */
@Composable
fun KeyHintRow(
    pairs: List<KeyHintPair>,
    modifier: Modifier = Modifier,
) {
    val spoken = spokenHint(pairs)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HintMinHeight)
            .clearAndSetSemantics { text = AnnotatedString(spoken) },
        horizontalArrangement = Arrangement.spacedBy(HintPairGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (pair in pairs) KeyHint(pair.key, pair.action, pair.round)
    }
}
