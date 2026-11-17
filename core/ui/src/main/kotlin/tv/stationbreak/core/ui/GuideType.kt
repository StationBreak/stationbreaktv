// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * Manrope, from the APK only: the four static weights from the upstream repository (see `core/ui/FONTS.md`). There is
 * no variable font and no separate tabular cut. Times and channel numbers get tabular figures from the OpenType `tnum`
 * feature on the same files; everything else keeps proportional figures.
 */
internal object GuideType {
    val Proportional =
        FontFamily(
            Font(R.font.manrope_regular, FontWeight.Normal),
            Font(R.font.manrope_medium, FontWeight.Medium),
            Font(R.font.manrope_semibold, FontWeight.SemiBold),
            Font(R.font.manrope_bold, FontWeight.Bold),
        )
}

/** Programme names, menu labels and prose. Proportional figures. */
fun guideText(
    size: TextUnit,
    weight: FontWeight,
) = TextStyle(
    fontFamily = GuideType.Proportional,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size,
    platformStyle = NoFontPadding,
)

/** Line height of [guideProse]: 1.2 times the size, so a second line does not sit on the first one's descenders. */
const val PROSE_LINE_HEIGHT = 1.2f

/**
 * [guideText] for text that can wrap onto a second line (a notice, a two-line row): the same face, with a 1.2x line
 * height instead of the single-line chrome's tight one. Single-line labels keep [guideText] so their boxes keep their
 * metrics.
 */
fun guideProse(
    size: TextUnit,
    weight: FontWeight,
) = guideText(size, weight).copy(lineHeight = size * PROSE_LINE_HEIGHT)

/** Letter spacing of the teletype flags and clock: 0.13em (DESIGN.md, "teletype"). */
private const val TELETYPE_TRACKING_EM = 0.13f

/**
 * The teletype (DESIGN.md): Space Mono Bold with 0.13em tracking, for the NOW flag, the banner's flag and the guide's
 * clock. A font choice only: colour comes from the caller, and the text is set in capitals by the caller.
 */
fun guideTeletype(size: TextUnit) =
    guideText(size, FontWeight.Bold).copy(
        fontFamily = BroadcastType.Mono,
        letterSpacing = size * TELETYPE_TRACKING_EM,
    )

/** Times, ranges, the clock and channel numbers. Tabular figures. */
fun guideFigures(
    size: TextUnit,
    weight: FontWeight,
) = guideText(size, weight).copy(
    fontFeatureSettings = "tnum",
)

private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
