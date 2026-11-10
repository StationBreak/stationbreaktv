// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.R
import java.time.ZoneId
import java.util.Locale

/** The tune banner: 2 dp progress line and a 54 dp row inside a 1 dp border. */
private val TuneHeight = 58.dp

/** The Select banner: the tune banner and a 1 dp rule and a 43 dp row of buttons, 102 dp in all. */
private val SelectHeight = 102.dp
private val ProgressHeight = 2.dp
private val RowHeight = 54.dp
private val SidePadding = 16.dp
private val LabelSize = 8.5.sp
private val NumberSize = 13.sp
private val TitleSize = 15.sp
private val MetaSize = 11.sp
private val FlipNumberSize = 20.sp
private val FlipNameSize = 15.sp
private val NextMaxWidth = 280.dp

/**
 * The tune banner, in the guide's design (Dark tokens, Manrope): a 2 dp progress line along the top, then one row with
 * the channel's mark and number, the title and one line (the label, the episode line, the slot, the minutes left), and
 * on the right the "Next" line. The banner the viewer asked for ([Banner.actions]) adds a rule and a row with an Info
 * and a Last button, which call [onInfo] and [onLast], and a Live button (while [behind]: the viewer is paused or
 * behind
 * live) that calls [onLive]. The progress line and the minutes left are redrawn once a minute from [clock]. Inside the
 * 5% safe area.
 */
@Suppress("LongParameterList") // The banner, its three actions and a clock a test can set.
@Composable
internal fun CableBanner(
    banner: Banner,
    modifier: Modifier = Modifier,
    onInfo: () -> Unit = {},
    onLast: () -> Unit = {},
    onLive: () -> Unit = {},
    behind: Boolean = false,
    clock: () -> Long = System::currentTimeMillis,
) {
    ChromeTheme {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
            BannerStrip(
                banner,
                Modifier.align(Alignment.BottomCenter).padding(start = safe.left, end = safe.left, bottom = safe.top),
                Actions(onInfo, onLast, onLive),
                behind,
                clock,
            )
        }
    }
}

/**
 * The bar of a channel flip, and of a card with no show to name (an ident or slate with nothing coming up): the
 * channel's mark, number and name in the banner's own chrome, and no program lines.
 */
@Composable
internal fun ChannelBanner(
    channel: ChannelTag,
    modifier: Modifier = Modifier,
) {
    ChromeTheme {
        val colors = LocalGuideColors.current
        BoxWithConstraints(modifier.fillMaxSize()) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = safe.left, end = safe.left, bottom = safe.top)
                    .fillMaxWidth()
                    .height(TuneHeight)
                    .chromeSurface(colors)
                    .padding(horizontal = SidePadding)
                    .testTag(LiveTags.BANNER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelMarkBox(channel.recipeKey, channel.name)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = channelNumber(channel.number),
                    style = guideFigures(FlipNumberSize, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = channel.name,
                    modifier = Modifier.weight(1f).testTag(LiveTags.BANNER_CHANNEL),
                    style = guideText(FlipNameSize, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun channelNumber(number: Int) = String.format(Locale.ROOT, "%02d", number)

/** The overlay's channel line: the number as the banner writes it, a dot, then the name. */
internal fun channelLine(
    number: Int,
    name: String,
) = channelNumber(number) + " · " + name

@Composable
private fun BannerStrip(
    banner: Banner,
    modifier: Modifier,
    actions: Actions,
    behind: Boolean,
    clock: () -> Long,
) {
    val colors = LocalGuideColors.current
    val context = LocalContext.current
    val programCopy = remember(context) { ProgramCopy.from(context.resources) }
    val is24h = remember(context) { DateFormat.is24HourFormat(context) }
    val slot =
        remember(banner, is24h) {
            ProgramText.slot(banner.slotStartMs, banner.slotEndMs, ZoneId.systemDefault(), is24h)
        }
    val episode = remember(banner) { BannerText.episodeLine(banner.info, programCopy) }
    val next =
        remember(banner, is24h) {
            banner.next?.let { BannerText.nextLine(it, programCopy, ZoneId.systemDefault(), is24h) }
        }
    val now = rememberMinuteClock(clock)
    Column(
        modifier
            .fillMaxWidth()
            .height(if (banner.actions) SelectHeight else TuneHeight)
            .chromeSurface(colors)
            .padding(1.dp)
            .testTag(LiveTags.BANNER),
    ) {
        if (banner.label == BannerLabel.UpNext) {
            Spacer(Modifier.height(ProgressHeight))
        } else {
            ProgressLine(banner.slotStartMs, banner.slotEndMs, programCopy, now)
        }
        BannerRow(banner, BannerLines(episode, slot, next, rememberStreamLine(banner)), programCopy, now)
        if (banner.actions) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            ActionsRow(banner.hasLast, actions, behind)
        }
    }
}

/**
 * What the banner's row writes besides the title: the episode line, the slot, the "Next" line, and the stream line
 * (what plays: information only, not focusable).
 */
private class BannerLines(
    val episode: String?,
    val slot: String,
    val next: String?,
    val stream: String?,
)

/** The 54 dp row: the mark and number, the title and meta line, and the "Next" line on the right. */
@Composable
private fun BannerRow(
    banner: Banner,
    lines: BannerLines,
    programCopy: ProgramCopy,
    now: LongState,
) {
    val colors = LocalGuideColors.current
    Row(
        Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = SidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelMarkBox(banner.recipeKey, banner.channelName)
        Spacer(Modifier.width(10.dp))
        Text(
            text = channelNumber(banner.number),
            style = guideFigures(NumberSize, FontWeight.Medium),
            color = colors.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            banner.title?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().testTag(LiveTags.BANNER_TITLE),
                    style = guideText(TitleSize, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetaLine(banner, lines.episode, lines.slot, programCopy, now)
        }
        val stream = lines.stream?.takeIf { it.isNotEmpty() }
        if (lines.next != null || stream != null) {
            Column(
                Modifier.padding(start = SidePadding).widthIn(max = NextMaxWidth),
                horizontalAlignment = Alignment.End,
            ) {
                lines.next?.let { RightLine(it, LiveTags.BANNER_NEXT) }
                stream?.let { RightLine(it, LiveTags.BANNER_STREAM) }
            }
        }
    }
}

/** One of the two right-aligned 11 sp lines of the row: the "Next" line, and the stream line under it. */
@Composable
private fun RightLine(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        modifier = Modifier.testTag(tag),
        style = guideText(MetaSize, FontWeight.Normal),
        color = LocalGuideColors.current.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The one 11 sp line under the title: the label (NOW, TUNING IN, UP NEXT or FILLING IN), the episode line when there is
 * one, the slot, and the minutes left (not on a card for what comes next, which has not begun). The episode line gives
 * way first when the line is long.
 */
@Composable
private fun MetaLine(
    banner: Banner,
    episode: String?,
    slot: String,
    copy: ProgramCopy,
    now: LongState,
) {
    val colors = LocalGuideColors.current
    val style = guideText(MetaSize, FontWeight.Normal)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelText(banner.label)).uppercase(LocalConfiguration.current.locales[0]),
            style = guideText(LabelSize, FontWeight.Bold).copy(letterSpacing = 0.6.sp),
            color = colors.accent,
            maxLines = 1,
        )
        episode?.let {
            Separator()
            Text(
                text = it,
                modifier = Modifier.weight(1f, fill = false).testTag(LiveTags.BANNER_SUBTITLE),
                style = style,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Separator()
        Text(
            text = slot,
            modifier = Modifier.testTag(LiveTags.BANNER_SLOT),
            style = guideFigures(MetaSize, FontWeight.Normal),
            color = colors.textSecondary,
            maxLines = 1,
        )
        if (banner.label != BannerLabel.UpNext) {
            Separator()
            Text(
                text = copy.minutesLeft(BannerText.minutesLeft(banner.slotStartMs, banner.slotEndMs, now.longValue)),
                style = style,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Separator() {
    Text(
        text = SEPARATOR,
        style = guideText(MetaSize, FontWeight.Normal),
        color = LocalGuideColors.current.textSecondary,
        maxLines = 1,
    )
}

private const val SEPARATOR = " · "

private fun labelText(label: BannerLabel) =
    when (label) {
        BannerLabel.TuningIn -> R.string.live_label_tuning_in
        BannerLabel.Now -> R.string.live_label_now
        BannerLabel.UpNext -> R.string.live_label_up_next
        BannerLabel.FillingIn -> R.string.live_label_filling_in
    }
