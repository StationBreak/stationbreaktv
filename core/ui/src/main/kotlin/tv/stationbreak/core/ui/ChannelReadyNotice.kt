// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val NoticeShape = RoundedCornerShape(2.dp)
private val NoticeMaxWidth = 480.dp

/** The tag of [ChannelReadyNotice]'s text, for tests. */
object ChannelReadyNoticeTags {
    const val NOTICE = "readyNotice"
}

/**
 * Says a channel the viewer switched on is ready (#776): "352 Neon Rain is ready" for one, "2 channels are ready" for
 * more. It only informs: it holds no focus and takes no key, so it is drawn with no popup, dialog, focus target or
 * click handler, and Back and the D-pad go on to the screen under it. A screen reader says it once, as it appears. It
 * is opaque, so what is under it does not show through. It sits top-left inside the safe area, clear of live TV's
 * banner (bottom), the guide's picture (top-right) and the programme info panel (bottom-left); on the Channels list it
 * covers the heading, which nothing can be done with. The caller decides when it shows and when it goes. Each of
 * [channels] is a number and a name.
 */
@Composable
fun ChannelReadyNotice(
    channels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return
    val text =
        if (channels.size == 1) {
            val (number, name) = channels.single()
            stringResource(R.string.ready_notice_one, number, name)
        } else {
            LocalContext.current.resources.getQuantityString(R.plurals.ready_notice_many, channels.size, channels.size)
        }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
        val colors = LocalGuideColors.current
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = safe.left, top = safe.top)
                .widthIn(max = NoticeMaxWidth)
                .clip(NoticeShape)
                .background(colors.page)
                .border(1.dp, colors.line, NoticeShape)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = text,
                modifier = Modifier.testTag(ChannelReadyNoticeTags.NOTICE),
                style = guideText(16.sp, FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
