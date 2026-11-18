// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.ui.graphics.Color
import tv.stationbreak.core.ui.BrandColors

/**
 * Which ground Stan stands on, and so what each of his colour tokens is. DESIGN.md: on paper, ink and cream trade
 * places (his cabinet turns ink and his screen cream); orange stays for shapes; his grey (the teddy, the plaster)
 * becomes dark grey so it still reads. The paper ground is paper (#F5F1E7) toward paper edge (#E6DFD0), as on the
 * brand sheet's light screens.
 */
@Suppress("LongParameterList") // One field per colour role of a ground; each ground is one value of the enum.
enum class StanPalette(
    internal val cream: Color,
    internal val ink: Color,
    internal val grey: Color,
    /** The ground at its lift (the middle) and at its edge. */
    internal val groundLift: Color,
    internal val groundEdge: Color,
    /** The colour of the halftone dots on the ground (at 4.5%) and of the shadow under Stan, with its opacity. */
    internal val dots: Color,
    internal val shadow: Color,
    internal val shadowAlpha: Float,
) {
    /** Warm ink ground, cream Stan: the default, how it is watched from a couch. */
    Dark(
        cream = BrandColors.Cream,
        ink = BrandColors.Ink,
        grey = BrandColors.Grey,
        groundLift = BrandColors.ScreenLift,
        groundEdge = BrandColors.ScreenEdge,
        dots = BrandColors.Cream,
        shadow = BrandColors.Black,
        shadowAlpha = .4f,
    ),

    /** Paper ground, ink Stan (cabinet ink, screen cream). */
    Paper(
        cream = BrandColors.Ink,
        ink = BrandColors.Cream,
        grey = BrandColors.DarkGrey,
        groundLift = BrandColors.Paper,
        groundEdge = BrandColors.PaperEdge,
        dots = BrandColors.Ink,
        shadow = BrandColors.Ink,
        shadowAlpha = .13f,
    ),
    ;

    /** The colour of a token on this ground. */
    fun color(token: StanColor): Color =
        when (token) {
            StanColor.Cream -> cream
            StanColor.Ink -> ink
            StanColor.Accent -> BrandColors.Orange
            StanColor.Grey -> grey
            StanColor.Black -> BrandColors.Black
        }
}
