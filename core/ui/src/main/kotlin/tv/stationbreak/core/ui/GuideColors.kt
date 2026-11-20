// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Guide tokens, dark or light (the broadcast-EPG overhaul, #657). Dark is the default. The choice is the guide's own
 * and is never taken from the TV's system theme. Dark is the brand's warm-ink palette in solid tokens (ink, panel,
 * line, cream, muted, orange; see DESIGN.md). Light is the brand's paper mode, also solid tokens (paper, paper edge,
 * deep orange, dark grey, ink; see DESIGN.md). Contrast is judged on the composite. Roles the overhaul removes keep
 * their field until the code that reads them goes: they are [Color.Transparent] meanwhile.
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
     * The brand muted in Dark, dark grey in Light: it holds 3:1 on the page and a card, so for strokes only (chevrons,
     * ticks, rules), never for text, which uses [textSecondary].
     * [GuidePaletteTest] fails if any file but the cells reads it.
     */
    val textSupporting: Color,
    val programmeText: Color,
    val accent: Color,
    val focusFill: Color,
    val focusText: Color,
    /** Secondary text on the focus fill: ink at 90% in Dark, cream at 95% in Light, the least that holds 4.5:1. */
    val focusTextSecondary: Color,
    val nowLine: Color,
    /** The now-line through the grid: 35% (60% in Light, where 35% is lost on the paper), so it may cross cells. */
    val nowRailAlpha: Float,
    val progressFill: Color,
    val progressTrack: Color,
    /** The 1 dp rules and ticks of the timeline. */
    val line: Color,
    /** The 1 dp outline of a hint key cap: the brand grey in Dark, or the paper line in Light. */
    val keyCapStroke: Color,
    /** A section flag's text on a sheet: the brand grey in Dark (DESIGN.md `section-flag`), dark grey in Light. */
    val sectionFlag: Color,
    /**
     * The ink the channel marks are tinted with: their mono render drawn in this colour (Light), or null to draw the
     * colour render as it is (Dark). See `channelmarks` in `core/ui/CHANNELMARKS.md`.
     */
    val markTint: Color? = null,
    /**
     * A sheet drawn over the guide (the menu sheet, a channel's properties): its surface is the brand panel
     * (#262320) in Dark, a step lighter than the paper in Light, so a sheet stands off the dimmed guide behind it in
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
            keyCapStroke = keyCapStroke,
            flag = sectionFlag,
        )

    companion object {
        val Dark =
            GuideColors(
                page = BrandColors.Ink,
                pageGradientStart = BrandColors.Ink,
                card = BrandColors.Panel,
                textPrimary = BrandColors.Cream,
                textSecondary = BrandColors.Muted,
                textSupporting = BrandColors.Muted,
                programmeText = BrandColors.Cream,
                accent = BrandColors.Orange,
                focusFill = BrandColors.Orange,
                focusText = BrandColors.Ink,
                focusTextSecondary = BrandColors.Ink.copy(alpha = FOCUS_SECONDARY_ALPHA),
                nowLine = BrandColors.Orange,
                nowRailAlpha = NOW_LINE_ALPHA,
                progressFill = BrandColors.Orange,
                progressTrack = BrandColors.Line,
                line = BrandColors.Line,
                keyCapStroke = BrandColors.Grey,
                sectionFlag = BrandColors.Grey,
                sheet = DarkSheet,
            )

        /**
         * The brand's paper mode on the same structure: a flat paper page (no gradient), a paper-edge card a step down,
         * ink text, dark grey for secondary text and strokes, and deep orange for words, the now-line, progress and
         * the focus fill (with cream on it). Ink and cream trade places from Dark. Light is the viewer's choice for
         * bright rooms. The hairlines and the track are derived solids ([BrandColors.PaperLine],
         * [BrandColors.PaperTrack]).
         */
        val Light =
            GuideColors(
                page = BrandColors.Paper,
                pageGradientStart = BrandColors.Paper,
                card = BrandColors.PaperEdge,
                textPrimary = BrandColors.Ink,
                textSecondary = BrandColors.DarkGrey,
                textSupporting = BrandColors.DarkGrey,
                programmeText = BrandColors.Ink,
                accent = BrandColors.DeepOrange,
                focusFill = BrandColors.DeepOrange,
                focusText = BrandColors.Cream,
                focusTextSecondary = BrandColors.Cream.copy(alpha = FOCUS_SECONDARY_ALPHA_LIGHT),
                nowLine = BrandColors.DeepOrange,
                nowRailAlpha = NOW_LINE_ALPHA_LIGHT,
                progressFill = BrandColors.DeepOrange,
                progressTrack = BrandColors.PaperTrack,
                line = BrandColors.PaperLine,
                keyCapStroke = BrandColors.PaperLine,
                sectionFlag = BrandColors.DarkGrey,
                markTint = BrandColors.Ink,
                sheet =
                    SheetColors(
                        surface = BrandColors.PaperLift,
                        line = BrandColors.Ink.copy(alpha = SHEET_LINE_ALPHA_LIGHT),
                        ratingBadge = LIGHT_RATING_BADGE,
                    ),
            )
    }
}

/** Dark unless a caller provides the saved choice. Never the system theme. */
val LocalGuideColors = staticCompositionLocalOf { GuideColors.Dark }

/** The Dark sheet, shared with [PanelColors.Dark]. */
internal val DarkSheet =
    SheetColors(surface = BrandColors.Panel, line = BrandColors.Line, ratingBadge = Color(0xFFFFD166))

private val LIGHT_RATING_BADGE = Color(0xFF7A4B00)
private const val SHEET_LINE_ALPHA_LIGHT = 0.20f
private const val FOCUS_SECONDARY_ALPHA = 0.90f
private const val FOCUS_SECONDARY_ALPHA_LIGHT = 0.95f
private const val NOW_LINE_ALPHA = 0.35f
private const val NOW_LINE_ALPHA_LIGHT = 0.60f
