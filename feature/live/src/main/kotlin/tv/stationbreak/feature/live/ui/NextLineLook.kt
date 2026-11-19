// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.NextProgram
import java.time.ZoneId

/** How the "Up next" line is worded and drawn: the copy, the clock format for its time, and its text style. */
internal class NextLineLook(
    val copy: ProgramCopy,
    val is24h: Boolean,
    val style: TextStyle,
)

/**
 * The banner's "Up next: <title> at <time>" line, in the place [modifier] gives it. The title is cut
 * ([BannerText.nextLine]) until the whole line, measured in its own style at the TV's density and font scale, fits the
 * width the row leaves it, so the time always shows. A screen reader is given the line with the whole title.
 */
@Composable
internal fun NextLine(
    next: NextProgram,
    look: NextLineLook,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val (shown, whole) =
            remember(next, look.copy, look.is24h, look.style, measurer, maxWidth) {
                val time = ProgramText.time(next.startMs, ZoneId.systemDefault(), look.is24h)
                val fits = { line: String ->
                    measurer.measure(line, look.style, maxLines = 1, softWrap = false).size.width <= maxWidth
                }
                BannerText.nextLine(next, time, look.copy, fits) to BannerText.nextLine(next, time, look.copy)
            }
        Text(
            text = shown,
            modifier = Modifier.testTag(LiveTags.BANNER_NEXT).semantics { contentDescription = whole },
            style = look.style,
            color = LocalGuideColors.current.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
