// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import tv.stationbreak.core.ui.R as UiR

/**
 * Manrope, from the APK only. [Tabular] is the tabular-figure cut for times and channel numbers; [Proportional] is
 * everything else. `tnum` is set on the tabular styles as well, so a renderer that ignores the cut still asks for
 * tabular figures. The variable font is bundled and not used here.
 */
internal object GuideType {
    val Proportional =
        FontFamily(
            Font(UiR.font.manrope_regular, FontWeight.Normal),
            Font(UiR.font.manrope_medium, FontWeight.Medium),
            Font(UiR.font.manrope_semibold, FontWeight.SemiBold),
            Font(UiR.font.manrope_bold, FontWeight.Bold),
        )

    val Tabular =
        FontFamily(
            Font(UiR.font.manrope_tabular_regular, FontWeight.Normal),
            Font(UiR.font.manrope_tabular_medium, FontWeight.Medium),
            Font(UiR.font.manrope_tabular_semibold, FontWeight.SemiBold),
            Font(UiR.font.manrope_tabular_bold, FontWeight.Bold),
        )
}

/** Programme names, menu labels and prose. Proportional figures. */
internal fun guideText(
    size: TextUnit,
    weight: FontWeight,
) = TextStyle(
    fontFamily = GuideType.Proportional,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size,
    platformStyle = NoFontPadding,
)

/** Times, ranges, the clock and channel numbers. Tabular figures. */
internal fun guideFigures(
    size: TextUnit,
    weight: FontWeight,
) = guideText(size, weight).copy(
    fontFamily = GuideType.Tabular,
    fontFeatureSettings = "tnum",
)

private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
