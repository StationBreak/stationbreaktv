// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

private val HeadlineSize = 40.sp
private val ScreenBodySize = 20.sp
private val ScreenDetailSize = 18.sp

private fun TextStyle.inManrope(): TextStyle = copy(fontFamily = GuideType.Proportional)

/**
 * tv-material's type scale with the app's face, Manrope, on every slot, so a screen that does not pick a family still
 * reads as StationBreak. Sizes are tv-material's, except `headlineLarge`: the 40 sp screen title that onboarding used
 * to set on each screen. The guide and live set their own sizes through `guideText`.
 */
val StationBreakTypography: Typography =
    Typography().let {
        Typography(
            displayLarge = it.displayLarge.inManrope(),
            displayMedium = it.displayMedium.inManrope(),
            displaySmall = it.displaySmall.inManrope(),
            headlineLarge = it.headlineLarge.inManrope().copy(fontSize = HeadlineSize),
            headlineMedium = it.headlineMedium.inManrope(),
            headlineSmall = it.headlineSmall.inManrope(),
            titleLarge = it.titleLarge.inManrope(),
            titleMedium = it.titleMedium.inManrope(),
            titleSmall = it.titleSmall.inManrope(),
            bodyLarge = it.bodyLarge.inManrope(),
            bodyMedium = it.bodyMedium.inManrope(),
            bodySmall = it.bodySmall.inManrope(),
            labelLarge = it.labelLarge.inManrope(),
            labelMedium = it.labelMedium.inManrope(),
            labelSmall = it.labelSmall.inManrope(),
        )
    }

/** Reading text on a full screen (onboarding's messages, buttons and fields): 20 sp, the 10-foot body size. */
val Typography.screenBody: TextStyle get() = bodyLarge.copy(fontSize = ScreenBodySize)

/** Supporting text under [screenBody]: 18 sp. */
val Typography.screenDetail: TextStyle get() = bodyLarge.copy(fontSize = ScreenDetailSize)
