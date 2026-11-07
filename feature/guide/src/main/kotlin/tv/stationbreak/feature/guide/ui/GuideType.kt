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
 * Manrope, from the APK only: the four static weights from the upstream repository (see `core/ui/FONTS.md`). There is
 * no variable font and no separate tabular cut. Times and channel numbers get tabular figures from the OpenType `tnum`
 * feature on the same files; everything else keeps proportional figures.
 */
internal object GuideType {
    val Proportional =
        FontFamily(
            Font(UiR.font.manrope_regular, FontWeight.Normal),
            Font(UiR.font.manrope_medium, FontWeight.Medium),
            Font(UiR.font.manrope_semibold, FontWeight.SemiBold),
            Font(UiR.font.manrope_bold, FontWeight.Bold),
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
    fontFeatureSettings = "tnum",
)

private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
