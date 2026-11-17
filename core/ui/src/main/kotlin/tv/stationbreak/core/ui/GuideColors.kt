// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Guide tokens, dark or light (the broadcast-EPG overhaul, #657). Dark is the default. The choice is the guide's own
 * and is never taken from the TV's system theme. Dark is the brand's warm-ink palette in solid tokens (ink, panel,
 * line, cream, muted, orange; see DESIGN.md). Light's neutral surfaces and text are one ink at a fixed alpha, drawn
 * over [page]. Contrast is judged on the composite. Roles the overhaul removes keep their field until the code that
 * reads them goes: they are [Color.Transparent] meanwhile.
 *
 * The guide and live TV's banner draw these themselves (the banner always uses [Dark]). The cream-ring treatment in
 * core:ui stays for screens that have not moved yet.
 */
@Suppress("LongParameterList") // One field per Guide A colour role.
class GuideColors(
    val page: Color,
    val pageGradientStart: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /**
     * A dim warm grey in Dark (#736D63, 3.05:1 on a card), 52% ink in Light: the least that holds 3:1 on the page and
     * a card, so for strokes only (chevrons, ticks, rules), never for text, which needs [textSecondary]'s 4.5:1.
     * [GuidePaletteTest] fails if any file but the cells reads it.
     */
    val textSupporting: Color,
    val programmeText: Color,
    val accent: Color,
    val focusFill: Color,
    val focusText: Color,
    /** Secondary text on the focus fill: the ink at 90%, the least that keeps 11 sp text at 4.5:1 in both themes. */
    val focusTextSecondary: Color,
    val nowLine: Color,
    /** The now-line through the grid: 35% (60% in Light, where 35% is lost on the grey), so it may cross cells. */
    val nowRailAlpha: Float,
    val progressFill: Color,
    val progressTrack: Color,
    /** The 1 dp rules and ticks of the timeline. */
    val line: Color,
    /** The 1 dp outline of a hint key cap: the brand grey in Dark, or ink in Light. */
    val keyCapStroke: Color,
    /**
     * The ink the channel marks are tinted with: their mono render drawn in this colour (Light), or null to draw the
     * colour render as it is (Dark). See `channelmarks` in `core/ui/CHANNELMARKS.md`.
     */
    val markTint: Color? = null,
    /**
     * A sheet drawn over the guide (the menu sheet, a channel's properties): its surface is the brand panel
     * (#262320) in Dark, a step lighter than the page in Light, so a sheet stands off the dimmed guide behind it in
     * both.
     */
    val sheet: SheetColors,
) {
    /** What a panel drawn over the guide (Settings, Libraries) takes from this theme, via `LocalPanelColors`. */
    val panel: PanelColors =
        PanelColors(
            ground = page,
            text = textPrimary,
            secondary = textSecondary,
            ring = focusFill,
            sheet = sheet,
        )

    companion object {
        val Dark =
            GuideColors(
                page = DARK_INK,
                pageGradientStart = DARK_INK,
                card = DARK_PANEL,
                textPrimary = DARK_CREAM,
                textSecondary = DARK_MUTED,
                textSupporting = DARK_SUPPORTING,
                programmeText = DARK_CREAM,
                accent = DARK_ORANGE,
                focusFill = DARK_ORANGE,
                focusText = DARK_INK,
                focusTextSecondary = DARK_INK.copy(alpha = FOCUS_SECONDARY_ALPHA),
                nowLine = DARK_ORANGE,
                nowRailAlpha = NOW_LINE_ALPHA,
                progressFill = DARK_ORANGE,
                progressTrack = DARK_LINE,
                line = DARK_LINE,
                keyCapStroke = DARK_GREY,
                sheet = DarkSheet,
            )

        /**
         * The same structure on a mid-light grey (about 83% lightness, no gradient: a white page is glaring on a TV),
         * with ink at alphas where Dark has solid warm tokens. The focus fill and the accent are darker oranges than
         * Dark's, the least that hold the floors on this page: the fill 3:1 over a card, the accent ([LIGHT_ACCENT],
         * used as text, as a line and for progress) 4.5:1 for ON NOW and 3:1 for the cell progress line over a card.
         */
        val Light =
            GuideColors(
                page = LIGHT_PAGE,
                pageGradientStart = LIGHT_PAGE,
                card = INK.copy(alpha = CARD_ALPHA_LIGHT),
                textPrimary = INK.copy(alpha = PRIMARY_ALPHA_LIGHT),
                textSecondary = INK.copy(alpha = SECONDARY_ALPHA_LIGHT),
                textSupporting = INK.copy(alpha = TERTIARY_ALPHA_LIGHT),
                programmeText = INK.copy(alpha = PRIMARY_ALPHA_LIGHT),
                accent = LIGHT_ACCENT,
                focusFill = LIGHT_FOCUS_FILL,
                focusText = Color(0xFFFFFFFF),
                focusTextSecondary = Color(0xFFFFFFFF).copy(alpha = FOCUS_SECONDARY_ALPHA),
                nowLine = LIGHT_ACCENT,
                nowRailAlpha = NOW_LINE_ALPHA_LIGHT,
                progressFill = LIGHT_ACCENT,
                progressTrack = INK.copy(alpha = TRACK_ALPHA_LIGHT),
                line = INK.copy(alpha = LINE_ALPHA_LIGHT),
                keyCapStroke = INK.copy(alpha = LINE_ALPHA_LIGHT),
                markTint = INK,
                sheet =
                    SheetColors(
                        surface = LIGHT_SHEET,
                        line = INK.copy(alpha = SHEET_LINE_ALPHA_LIGHT),
                        ratingBadge = LIGHT_RATING_BADGE,
                    ),
            )
    }
}

/** Dark unless a caller provides the saved choice. Never the system theme. */
val LocalGuideColors = staticCompositionLocalOf { GuideColors.Dark }

// The brand's warm-ink tokens (DESIGN.md), which Dark takes whole. Internal: [PanelColors.Dark] reads them too, and
// must not read them off [GuideColors] (an init cycle).
internal val DARK_INK = Color(0xFF1C1B19)
internal val DARK_PANEL = Color(0xFF262320)
internal val DARK_LINE = Color(0xFF3D3832)
internal val DARK_CREAM = Color(0xFFF3EEE3)
internal val DARK_MUTED = Color(0xFFB3AB9D)
internal val DARK_ORANGE = Color(0xFFE8642C)
private val DARK_GREY = Color(0xFFCFCBC2)
private val DARK_SUPPORTING = Color(0xFF736D63)

/** The Dark sheet, shared with [PanelColors.Dark]: that must not read it off [GuideColors] (an init cycle). */
internal val DarkSheet = SheetColors(surface = DARK_PANEL, line = DARK_LINE, ratingBadge = Color(0xFFFFD166))

// Light's text and surfaces are one ink at fixed alphas. Light has its own alphas: its page is darker than a
// near-white one, so the card, the rules and the track are stronger and its text is held at 4.5:1 on both the page and
// the card.
private val LIGHT_SHEET = Color(0xFFE4E6EA)
private val LIGHT_RATING_BADGE = Color(0xFF7A4B00)
private const val SHEET_LINE_ALPHA_LIGHT = 0.20f
private val INK = Color(0xFF111214)
private val LIGHT_PAGE = Color(0xFFD2D5DA)
private val LIGHT_ACCENT = Color(0xFF8F3811)
private val LIGHT_FOCUS_FILL = Color(0xFFAD4216)
private const val PRIMARY_ALPHA_LIGHT = 0.92f
private const val SECONDARY_ALPHA_LIGHT = 0.66f
private const val TERTIARY_ALPHA_LIGHT = 0.52f
private const val CARD_ALPHA_LIGHT = 0.12f
private const val FOCUS_SECONDARY_ALPHA = 0.90f
private const val TRACK_ALPHA_LIGHT = 0.14f
private const val LINE_ALPHA_LIGHT = 0.16f
private const val NOW_LINE_ALPHA = 0.35f
private const val NOW_LINE_ALPHA_LIGHT = 0.60f
