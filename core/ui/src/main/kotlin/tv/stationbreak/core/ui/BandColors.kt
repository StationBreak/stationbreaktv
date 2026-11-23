// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color

/**
 * The accents of the number bands (DESIGN.md "Band colours"): Tots green, Kids blue, Teens violet, and the neutral
 * stroke grey for Adults, which has no accent of its own. None is orange, red or yellow, which mean focus, now and
 * status. They are for shapes only (a stripe, a dot, a rule), 3:1 or better on the page, a card and a sheet, and always
 * sit next to the band's name; text keeps the usual secondary and primary colours. `GuideColorsTest` holds the
 * contrast.
 */
class BandColors(
    val tots: Color,
    val kids: Color,
    val teens: Color,
    val adults: Color,
) {
    companion object {
        val Dark =
            BandColors(
                tots = Color(0xFF6FCF97),
                kids = Color(0xFF5AB0E8),
                teens = Color(0xFFA98BE8),
                adults = BrandColors.Muted,
            )

        val Light =
            BandColors(
                tots = Color(0xFF2E8B57),
                kids = Color(0xFF1F6FB5),
                teens = Color(0xFF6B4FB8),
                adults = BrandColors.DarkGrey,
            )
    }
}
