// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * What a channel holds, as words (#757), in plain numbers: [hours] it airs (guests included), the [days] they make
 * from two up (else null), the [titles] counted, whether the list is "Home to:" ([homeTo]) or "Mostly:", the [names]
 * listed (the caller has already chosen up to six) and the [more] not listed. The guide's channel box and the
 * properties sheet draw the same value through [ChannelHoldingsLines], so they cannot word it two ways.
 */
data class HoldingsFigures(
    val hours: Long,
    val days: Long?,
    val titles: Int,
    val homeTo: Boolean,
    val names: List<String>,
    val more: Int,
)

/** The tags of the lines of [ChannelHoldingsLines]: the hours line, the list's label and the list. */
class HoldingsTags(
    val hours: String,
    val label: String,
    val names: String,
)

private const val SMALL_SP = 11
private const val NAMES_SP = 13
private const val NAMES_LINES = 2
private val LineGap = 2.dp

/** The names line: the titles comma-separated, then the count not listed. */
internal fun namesLine(
    names: List<String>,
    more: String?,
): String = (listOf(names.joinToString(", ")) + listOfNotNull(more)).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The hours line (11 sp), the list's label (11 sp) and the list (13 sp, two lines at most) of [figures], in [secondary]
 * and [primary]. With no names listed (a lookup that gave none) the label and the list are left out.
 */
@Composable
fun ChannelHoldingsLines(
    figures: HoldingsFigures,
    primary: Color,
    secondary: Color,
    tags: HoldingsTags,
    modifier: Modifier = Modifier,
) {
    val hours = pluralStringResource(R.plurals.holdings_hours, figures.hours.toInt(), figures.hours.toInt())
    val titles = pluralStringResource(R.plurals.holdings_titles, figures.titles, figures.titles)
    val line =
        if (figures.days == null) {
            stringResource(R.string.holdings_line, hours, titles)
        } else {
            val about = stringResource(R.string.holdings_days, figures.days)
            stringResource(R.string.holdings_line_days, hours, about, titles)
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            text = line,
            modifier = Modifier.testTag(tags.hours),
            style = guideText(SMALL_SP.sp, FontWeight.Normal),
            color = secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (figures.names.isNotEmpty()) {
            Text(
                text = stringResource(if (figures.homeTo) R.string.holdings_home_to else R.string.holdings_mostly),
                modifier = Modifier.testTag(tags.label),
                style = guideText(SMALL_SP.sp, FontWeight.Normal),
                color = secondary,
                maxLines = 1,
            )
            val more = if (figures.more > 0) stringResource(R.string.holdings_more, figures.more) else null
            Text(
                text = namesLine(figures.names, more),
                modifier = Modifier.testTag(tags.names),
                style = guideText(NAMES_SP.sp, FontWeight.Normal).copy(lineHeight = (NAMES_SP + 2).sp),
                color = primary,
                maxLines = NAMES_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
