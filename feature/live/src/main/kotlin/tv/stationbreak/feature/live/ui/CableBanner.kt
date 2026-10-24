// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.R
import java.time.ZoneId
import java.util.Locale

private const val OVERSCAN = 0.05f
private const val RULE_HEIGHT = 4
private const val TINT_ALPHA = 0.08f

private val CornerSize = 72.sp
private val NumberSize = 36.sp
private val TitleSize = 26.sp
private val LabelSize = 13.sp
private val DetailSize = 14.sp
private val ShadowOffset = Offset(3f, 3f)
private val BAR_HEIGHTS = listOf(5, 8, 12, 16)
private const val BAR_PULSE_MS = 600
private const val BAR_STAGGER_MS = 200
private const val BAR_LOW_ALPHA = 0.25f

/**
 * The cable-box banner (design: board D's layout in board E2's brand styling): the channel number large in the corner
 * (when [cornerNumber]), and a bottom strip with the number and name, the signal bars and label, the title, the
 * episode line when there is one, and the slot with how far in the viewer joined. Inside a 5% safe area.
 */
@Composable
internal fun CableBanner(
    banner: Banner,
    modifier: Modifier = Modifier,
    cornerNumber: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = maxWidth * OVERSCAN
        val edge = maxHeight * OVERSCAN
        if (cornerNumber) {
            Text(
                text = channelNumber(banner.number),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = side, top = edge),
                style =
                    TextStyle(
                        fontFamily = BroadcastType.Display,
                        fontSize = CornerSize,
                        color = BroadcastColors.Accent,
                        shadow = Shadow(BroadcastColors.Ink, ShadowOffset),
                    ),
            )
        }
        BannerStrip(banner, Modifier.align(Alignment.BottomCenter).padding(start = side, end = side, bottom = edge))
    }
}

/**
 * The strip of a card with no show to name (an ident or slate with nothing coming up): the number and name of the
 * channel, in the banner's own chrome, and no program lines.
 */
@Composable
internal fun ChannelBanner(
    channel: ChannelTag,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = maxWidth * OVERSCAN
        val edge = maxHeight * OVERSCAN
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = side, end = side, bottom = edge)
                .fillMaxWidth()
                .background(BroadcastColors.Ink)
                .testTag(LiveTags.BANNER),
        ) {
            Box(Modifier.fillMaxWidth().height(RULE_HEIGHT.dp).background(BroadcastColors.Accent))
            Row(Modifier.height(IntrinsicSize.Min)) { NumberBlock(channel.number, channel.name) }
        }
    }
}

private fun channelNumber(number: Int) = String.format(Locale.ROOT, "%02d", number)

@Composable
private fun BannerStrip(
    banner: Banner,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val copy = remember(context) { BannerCopy.from(context.resources) }
    val is24h = remember(context) { DateFormat.is24HourFormat(context) }
    val slot =
        remember(banner, is24h) {
            BannerText.slot(banner.slotStartMs, banner.slotEndMs, ZoneId.systemDefault(), is24h)
        }
    val joined = remember(banner) { BannerText.joined(banner.joinedMs, copy) }
    val episode = remember(banner) { BannerText.episodeLine(banner.info, copy) }
    Column(
        modifier
            .fillMaxWidth()
            .background(BroadcastColors.Ink)
            .testTag(LiveTags.BANNER),
    ) {
        Box(Modifier.fillMaxWidth().height(RULE_HEIGHT.dp).background(BroadcastColors.Accent))
        Row(Modifier.height(IntrinsicSize.Min)) {
            NumberBlock(banner.number, banner.channelName)
            Column(
                Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                LabelLine(banner.label)
                banner.title?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth().testTag(LiveTags.BANNER_TITLE),
                        style = TextStyle(fontFamily = BroadcastType.Display, fontSize = TitleSize),
                        color = BroadcastColors.Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                episode?.let { Detail(it, LiveTags.BANNER_SUBTITLE, Modifier.fillMaxWidth()) }
                Row(Modifier.fillMaxWidth()) {
                    Detail(slot, LiveTags.BANNER_SLOT, Modifier.weight(1f, fill = false))
                    joined?.let {
                        Detail(SEPARATOR, null)
                        Detail(it, LiveTags.BANNER_JOINED, Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
    }
}

private const val SEPARATOR = " · "

@Composable
private fun NumberBlock(
    number: Int,
    name: String,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(min = 150.dp, max = 280.dp)
            .background(BroadcastColors.Cream.copy(alpha = TINT_ALPHA))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = channelNumber(number),
            style = TextStyle(fontFamily = BroadcastType.Display, fontSize = NumberSize),
            color = BroadcastColors.Cream,
            maxLines = 1,
        )
        Text(
            text = name.uppercase(),
            modifier = Modifier.testTag(LiveTags.BANNER_CHANNEL),
            style = mono(LabelSize, letterSpacing = 1.5.sp),
            color = BroadcastColors.Ground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LabelLine(label: BannerLabel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SignalBars()
        Text(
            text = stringResource(labelText(label)),
            style = mono(LabelSize, letterSpacing = 2.sp),
            color = BroadcastColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun labelText(label: BannerLabel) =
    when (label) {
        BannerLabel.TuningIn -> R.string.live_label_tuning_in
        BannerLabel.Now -> R.string.live_label_now
        BannerLabel.UpNext -> R.string.live_label_up_next
        BannerLabel.FillingIn -> R.string.live_label_filling_in
    }

@Composable
private fun Detail(
    text: String,
    tag: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = if (tag == null) modifier else modifier.testTag(tag),
        style = mono(DetailSize),
        color = BroadcastColors.Ground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun mono(
    size: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = BroadcastType.Mono,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    letterSpacing = letterSpacing,
)

/** Four bars that pulse one after the other, as on a cable box that is finding the signal. Purely decorative. */
@Composable
private fun SignalBars() {
    val pulse = rememberInfiniteTransition(label = "signal")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        BAR_HEIGHTS.forEachIndexed { index, bar ->
            val alpha =
                pulse.animateFloat(
                    initialValue = BAR_LOW_ALPHA,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            tween(BAR_PULSE_MS, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse,
                            StartOffset(index * BAR_STAGGER_MS),
                        ),
                    label = "bar$index",
                )
            Box(
                Modifier
                    .size(width = 4.dp, height = bar.dp)
                    .graphicsLayer { this.alpha = alpha.value }
                    .background(BroadcastColors.Accent),
            )
        }
    }
}
