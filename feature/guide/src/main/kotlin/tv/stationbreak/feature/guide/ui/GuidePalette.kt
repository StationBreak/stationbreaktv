// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Guide A, dark or light (the Guide A tokens). Dark is the default. The choice is the guide's own and is never taken
 * from the TV's system theme. Opaque sRGB. [focusedRowWash] and [hintDivider] are the only roles that carry an alpha;
 * muted text is a colour, not a faded one.
 *
 * The guide draws these itself. The cream-ring treatment in core:ui stays for screens that have not moved yet.
 */
@Suppress("LongParameterList") // One field per Guide A colour role.
internal class GuideColors(
    val page: Color,
    val pageGradientStart: Color,
    val card: Color,
    val cardAlternate: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textSupporting: Color,
    val programmeText: Color,
    val channelText: Color,
    val accent: Color,
    val focusFill: Color,
    val focusText: Color,
    val nowChipFill: Color,
    val nowChipText: Color,
    val nowLine: Color,
    /** The now rail between rows. Light is opaque; dark keeps the reference's 80%. */
    val nowRailAlpha: Float,
    val progressFill: Color,
    val progressTrack: Color,
    val menuPillSelected: Color,
    val menuTextSelected: Color,
    val menuTextUnselected: Color,
    val menuPillFocused: Color,
    val menuTextFocused: Color,
    val focusedRowWash: Color,
    val keyHintPill: Color,
    val hintDivider: Color,
    val focusShadow: Color,
    /** One soft shadow under the focused card. Not part of the contrast claim. */
    val shadowAlpha: Float,
) {
    companion object {
        val Dark =
            GuideColors(
                page = Color(0xFF191A19),
                pageGradientStart = Color(0xFF212420),
                card = Color(0xFF282A28),
                cardAlternate = Color(0xFF252725),
                textPrimary = Color(0xFFF3EEE3),
                textSecondary = Color(0xFFB3B4AE),
                textSupporting = Color(0xFFCFCBC2),
                programmeText = Color(0xFFE0DDD5),
                channelText = Color(0xFFCCC9C0),
                accent = Color(0xFFE8642C),
                focusFill = Color(0xFFE8642C),
                focusText = Color(0xFF1C1B19),
                nowChipFill = Color(0xFFE8642C),
                nowChipText = Color(0xFF1C1B19),
                nowLine = Color(0xFFE8642C),
                nowRailAlpha = 0.8f,
                progressFill = Color(0xFFE8642C),
                progressTrack = Color(0xFF3E3C36),
                menuPillSelected = Color(0xFF30332F),
                menuTextSelected = Color(0xFFF3EEE3),
                menuTextUnselected = Color(0xFFB3B4AE),
                menuPillFocused = Color(0xFFE8642C),
                menuTextFocused = Color(0xFF1C1B19),
                focusedRowWash = Color(0xFF829087).copy(alpha = ROW_WASH_ALPHA),
                keyHintPill = Color(0xFF3C3C36),
                hintDivider = Color(0xFFB6AD9F).copy(alpha = HINT_DIVIDER_ALPHA),
                focusShadow = Color(0xFF000000),
                shadowAlpha = 0.12f,
            )

        val Light =
            GuideColors(
                page = Color(0xFFF3EEE3),
                pageGradientStart = Color(0xFFF8F4EC),
                card = Color(0xFFFFFCF6),
                cardAlternate = Color(0xFFFAF7F0),
                textPrimary = Color(0xFF1C1B19),
                textSecondary = Color(0xFF625D54),
                textSupporting = Color(0xFF625D54),
                programmeText = Color(0xFF1C1B19),
                channelText = Color(0xFF1C1B19),
                accent = Color(0xFFB74719),
                focusFill = Color(0xFFB74719),
                focusText = Color(0xFFFFFFFF),
                nowChipFill = Color(0xFFB74719),
                nowChipText = Color(0xFFFFFFFF),
                nowLine = Color(0xFFB74719),
                nowRailAlpha = 1f,
                progressFill = Color(0xFFB74719),
                progressTrack = Color(0xFFD6CEC0),
                menuPillSelected = Color(0xFFE0DACF),
                menuTextSelected = Color(0xFF1C1B19),
                menuTextUnselected = Color(0xFF625D54),
                menuPillFocused = Color(0xFFB74719),
                menuTextFocused = Color(0xFFFFFFFF),
                focusedRowWash = Color(0xFF756C5E).copy(alpha = ROW_WASH_ALPHA),
                keyHintPill = Color(0xFFE0DACF),
                hintDivider = Color(0xFF756C5E).copy(alpha = HINT_DIVIDER_ALPHA),
                focusShadow = Color(0xFF594431),
                shadowAlpha = 0.08f,
            )
    }
}

/** Dark unless a caller provides the saved choice. Never the system theme. */
internal val LocalGuideColors = staticCompositionLocalOf { GuideColors.Dark }

private const val ROW_WASH_ALPHA = 0.055f
private const val HINT_DIVIDER_ALPHA = 0.13f

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
