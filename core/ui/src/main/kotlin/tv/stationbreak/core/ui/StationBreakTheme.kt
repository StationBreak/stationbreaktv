// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The warm dark scheme, built from [BrandColors]: ink ground, panel for fills, cream and grey text, orange for focus
 * (`primary` is a focused button's fill, `border` the ring on a focused field or row,
 * `borderVariant` the muted edge of a resting control). `error` stays tv-material's.
 * `ContrastTest` checks the ratios.
 */
val StationBreakDarkColors =
    darkColorScheme(
        background = BrandColors.Ink,
        surface = BrandColors.Ink,
        surfaceVariant = BrandColors.Panel,
        onBackground = BrandColors.Cream,
        onSurface = BrandColors.Cream,
        onSurfaceVariant = BrandColors.Grey,
        primary = BrandColors.Orange,
        onPrimary = BrandColors.Ink,
        border = BrandColors.Orange,
        borderVariant = BrandColors.Muted,
    )

/** How strong the address hint is, as the alpha of `onSurfaceVariant` on the field fill. 0.9 keeps it above 4.5:1. */
const val HINT_ALPHA = 0.9f

/**
 * The app's theme: the warm brand dark scheme ([StationBreakDarkColors]), set explicitly (a TV is watched in a dim
 * room), with the app's [StationBreakTypography].
 */
@Composable
fun StationBreakTheme(content: @Composable () -> Unit) {
    val motionReduced by rememberMotionReduced()
    CompositionLocalProvider(LocalMotionReduced provides motionReduced) {
        MaterialTheme(colorScheme = StationBreakDarkColors, typography = StationBreakTypography, content = content)
    }
}
