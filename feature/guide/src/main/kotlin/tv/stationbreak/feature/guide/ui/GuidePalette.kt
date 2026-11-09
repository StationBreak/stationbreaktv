// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Guide tokens, dark or light (the broadcast-EPG overhaul, #657). Dark is the default. The choice is the guide's own
 * and is never taken from the TV's system theme. Neutral surfaces and text are one colour at a fixed alpha, drawn over
 * [page]; contrast is judged on the composite. Roles the overhaul removes keep their field until the code that reads
 * them goes: they are [Color.Transparent] meanwhile.
 *
 * The guide draws these itself. The cream-ring treatment in core:ui stays for screens that have not moved yet.
 */
@Suppress("LongParameterList") // One field per Guide A colour role.
internal class GuideColors(
    val page: Color,
    val pageGradientStart: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /**
     * 38% white (52% ink in Light): about 3.55:1 on the page, so for strokes only (chevrons, ticks, rules), never for
     * text, which needs [textSecondary]'s 4.5:1. [GuidePaletteTest] fails if any file but the cells reads it.
     */
    val textSupporting: Color,
    val programmeText: Color,
    val accent: Color,
    val focusFill: Color,
    val focusText: Color,
    /** Secondary text on the focus fill: 90%, the least that keeps 11 sp text at 4.5:1 in both themes. */
    val focusTextSecondary: Color,
    val nowLine: Color,
    /** The now-line through the grid: 35%, so it may cross cells without hiding them. */
    val nowRailAlpha: Float,
    val progressFill: Color,
    val progressTrack: Color,
    /** The 1 dp rules and ticks of the timeline. */
    val line: Color,
    /** The 1 dp outline of a hint key cap: 28% white, or ink. */
    val keyCapStroke: Color,
    /**
     * The ink the channel marks are tinted with: their mono render drawn in this colour (Light), or null to draw the
     * colour render as it is (Dark). See `channelmarks` in `feature/guide/CHANNELMARKS.md`.
     */
    val markTint: Color? = null,
) {
    companion object {
        val Dark =
            GuideColors(
                page = Color(0xFF0E0F11),
                pageGradientStart = Color(0xFF0E0F11),
                card = Color.White.copy(alpha = CARD_ALPHA),
                textPrimary = Color.White.copy(alpha = PRIMARY_ALPHA),
                textSecondary = Color.White.copy(alpha = SECONDARY_ALPHA_DARK),
                textSupporting = Color.White.copy(alpha = TERTIARY_ALPHA_DARK),
                programmeText = Color.White.copy(alpha = PRIMARY_ALPHA),
                accent = Color(0xFFE8642C),
                focusFill = Color(0xFFE8642C),
                focusText = Color(0xFF1A0D06),
                focusTextSecondary = Color(0xFF1A0D06).copy(alpha = FOCUS_SECONDARY_ALPHA),
                nowLine = Color(0xFFE8642C),
                nowRailAlpha = NOW_LINE_ALPHA,
                progressFill = Color(0xFFE8642C),
                progressTrack = Color.White.copy(alpha = TRACK_ALPHA),
                line = Color.White.copy(alpha = LINE_ALPHA),
                keyCapStroke = Color.White.copy(alpha = KEY_STROKE_ALPHA),
            )

        /** The same structure on a near-white neutral, with ink where Dark has white. */
        val Light =
            GuideColors(
                page = Color(0xFFF6F7F8),
                pageGradientStart = Color(0xFFF6F7F8),
                card = INK.copy(alpha = CARD_ALPHA),
                textPrimary = INK.copy(alpha = PRIMARY_ALPHA),
                textSecondary = INK.copy(alpha = SECONDARY_ALPHA_LIGHT),
                textSupporting = INK.copy(alpha = TERTIARY_ALPHA_LIGHT),
                programmeText = INK.copy(alpha = PRIMARY_ALPHA),
                accent = Color(0xFFB74719),
                focusFill = Color(0xFFB74719),
                focusText = Color(0xFFFFFFFF),
                focusTextSecondary = Color(0xFFFFFFFF).copy(alpha = FOCUS_SECONDARY_ALPHA),
                nowLine = Color(0xFFB74719),
                nowRailAlpha = NOW_LINE_ALPHA,
                progressFill = Color(0xFFB74719),
                progressTrack = INK.copy(alpha = TRACK_ALPHA),
                line = INK.copy(alpha = LINE_ALPHA),
                keyCapStroke = INK.copy(alpha = KEY_STROKE_ALPHA),
                markTint = INK,
            )
    }
}

/** Dark unless a caller provides the saved choice. Never the system theme. */
internal val LocalGuideColors = staticCompositionLocalOf { GuideColors.Dark }

// Text and surfaces are the one neutral (white in Dark, ink in Light) at fixed alphas, so every role steps from the
// page without a warm tint. Light's secondary and tertiary are stronger than Dark's: ink at 58% on the card is under
// 4.5:1.
private val INK = Color(0xFF111214)
private const val PRIMARY_ALPHA = 0.90f
private const val SECONDARY_ALPHA_DARK = 0.58f
private const val TERTIARY_ALPHA_DARK = 0.38f
private const val SECONDARY_ALPHA_LIGHT = 0.64f
private const val TERTIARY_ALPHA_LIGHT = 0.52f
private const val CARD_ALPHA = 0.06f
private const val FOCUS_SECONDARY_ALPHA = 0.90f
private const val TRACK_ALPHA = 0.08f
private const val LINE_ALPHA = 0.10f
private const val KEY_STROKE_ALPHA = 0.28f
private const val NOW_LINE_ALPHA = 0.35f

/** The guide's own appearance. [decode] is dark for anything that is not the light token, including a missing value. */
internal enum class GuideTheme {
    Dark,
    Light,
    ;

    val colors: GuideColors
        get() = if (this == Light) GuideColors.Light else GuideColors.Dark

    companion object {
        fun decode(stored: String?): GuideTheme = if (stored == encode(Light)) Light else Dark

        fun encode(theme: GuideTheme): String = theme.name
    }
}
