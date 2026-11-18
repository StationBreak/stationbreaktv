// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color

/** The brand's colour tokens, named as DESIGN.md names them. The one home for these values. */
internal object BrandColors {
    val Ink = Color(0xFF1C1B19)
    val Panel = Color(0xFF262320)
    val Line = Color(0xFF3D3832)
    val Cream = Color(0xFFF3EEE3)
    val Grey = Color(0xFFCFCBC2)
    val Muted = Color(0xFFB3AB9D)
    val Orange = Color(0xFFE8642C)

    /** Pure black: Stan's shadow on the ink ground (the study's `black` token, at 40%). */
    val Black = Color(0xFF000000)

    /** The ink ground's soft lift (#2E2A25), toward the middle of a full-screen ground. */
    val ScreenLift = Color(0xFF2E2A25)

    /** The ink ground toward the edges (#151412), the far end of the lift. */
    val ScreenEdge = Color(0xFF151412)

    /** The light mode (paper): ink and cream trade places. The light ground (#F5F1E7), flat in the guide. */
    val Paper = Color(0xFFF5F1E7)

    /** The light ground toward the edges (#E6DFD0): a step down from [Paper], so the guide's card. */
    val PaperEdge = Color(0xFFE6DFD0)

    /** Orange for words on paper (#AE400C): 5.1:1 against cream. */
    val DeepOrange = Color(0xFFAE400C)

    /** Small supporting text on paper (#5E5850): 6.1:1. */
    val DarkGrey = Color(0xFF5E5850)

    /** Derived, not from the brand sheet: ink at 16% over [Paper] (#D2CFC6), the 1 dp rules and key cap outline. */
    val PaperLine = Color(0xFFD2CFC6)

    /** Ink at 14% over [Paper] (#D7D3CA): the progress track in Light. */
    val PaperTrack = Color(0xFFD7D3CA)

    /** A step lighter than [Paper] (#FBF8F1): the Light sheet, so it stands off the dimmed guide. */
    val PaperLift = Color(0xFFFBF8F1)
}
