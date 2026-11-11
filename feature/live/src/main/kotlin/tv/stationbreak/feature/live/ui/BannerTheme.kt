// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.core.ui.mark.LocalMarkCache
import tv.stationbreak.core.ui.mark.MarkCaches
import tv.stationbreak.core.ui.mark.MarkVariant
import tv.stationbreak.core.ui.mark.markInitialsOf

/** The guide's corner radius: 2 dp, on the banner, the flip bar, the transport strip and the buttons. */
internal val ChromeShape = RoundedCornerShape(2.dp)

private const val SURFACE_ALPHA = 0.92f

/**
 * The live chrome over video always uses the guide's Dark tokens, whatever the guide's own theme is: it sits over a
 * picture, where light chrome glares (`docs/design/live-banner-and-tracks.md`, Part A).
 */
@Composable
internal fun ChromeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGuideColors provides GuideColors.Dark, content = content)
}

/** The banner's surface: `page` at 92%, a 1 dp `line` border and a 2 dp radius, with its content inside the border. */
internal fun Modifier.chromeSurface(colors: GuideColors): Modifier =
    this
        .clip(ChromeShape)
        .background(colors.page.copy(alpha = SURFACE_ALPHA))
        .border(1.dp, colors.line, ChromeShape)

/**
 * The channel's mark: its logo if it has one (found by [recipeKey], decoded off the main thread by the process-wide
 * cache the guide shares), else its initials.
 */
@Composable
internal fun ChannelMarkBox(
    recipeKey: String?,
    channelName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember(context) { MarkCaches.get(context) }
    LaunchedEffect(cache, recipeKey) { recipeKey?.let { cache.request(listOf(it), MarkVariant.Colour) } }
    val initials = remember(channelName) { markInitialsOf(channelName) }
    CompositionLocalProvider(LocalMarkCache provides cache) {
        Box(Modifier.testTag(LiveTags.BANNER_MARK)) { ChannelMark(recipeKey, initials, modifier) }
    }
}
