// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.ScreenLayout

/**
 * The padding that keeps an onboarding screen's content inside the shared safe area (the viewer's screen fit, 5% by
 * default, as the guide and live use), with its own [top] offset. Every screen takes it from here instead of keeping
 * a copy of the fraction. A screen inside a [SetupScaffold] takes the scaffold's padding instead (see there).
 */
@Composable
internal fun BoxWithConstraintsScope.safeAreaPadding(top: Dp): PaddingValues =
    safeAreaPadding(DpSize(maxWidth, maxHeight), LocalScreenFit.current, top)

/** The safe-area padding of a whole [screen] at [fit], with [top]; none at the end side unless [end]. */
internal fun safeAreaPadding(
    screen: DpSize,
    fit: ScreenFit,
    top: Dp,
    end: Boolean = true,
): PaddingValues {
    val safe = ScreenLayout.safeArea(screen, fit)
    return PaddingValues(
        start = safe.left,
        top = top,
        end = if (end) screen.width - safe.right else 0.dp,
        bottom = screen.height - safe.bottom,
    )
}

/** These paddings with their top replaced by [top]: a screen's own fixed offset from the top. */
@Composable
internal fun PaddingValues.withTop(top: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = top,
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}
