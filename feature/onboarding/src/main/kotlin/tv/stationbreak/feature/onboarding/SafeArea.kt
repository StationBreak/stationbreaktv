// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout

/**
 * The padding that keeps an onboarding screen's content inside the shared safe area (the viewer's screen fit, 5% by
 * default, as the guide and live use), with its own [top] offset. Every screen takes it from here instead of keeping
 * a copy of the fraction.
 *
 * Inside a [SetupScaffold] the safe area is the whole screen's, not the narrower content box's, and the end side is
 * Stan's column, which keeps its own inset.
 */
@Composable
internal fun BoxWithConstraintsScope.safeAreaPadding(top: Dp): PaddingValues {
    val frame = LocalSetupFrame.current
    val safe = ScreenLayout.safeArea(frame?.screen ?: DpSize(maxWidth, maxHeight), LocalScreenFit.current)
    return PaddingValues(
        start = safe.left,
        top = top,
        end = if (frame?.besideStan == true) 0.dp else maxWidth - safe.right,
        bottom = maxHeight - safe.bottom,
    )
}
