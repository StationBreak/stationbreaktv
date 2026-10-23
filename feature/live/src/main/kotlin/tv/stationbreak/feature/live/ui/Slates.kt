// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.R

// Card units, from board E2 and the icon: where the TV sits and where the lines start on the disc.
private const val DIFFICULTIES_TV_Y = 202f
private const val IDENT_TV_Y = 212f
private const val DIFFICULTIES_TOP = 272f
private const val IDENT_TOP = 278f
private const val NO_CHANNELS_TOP = 396f
private const val DISC_TEXT_WIDTH = 280f
private const val NO_CHANNELS_WIDTH = 230f
private const val TITLE_SIZE = 34f
private const val TITLE_LINE = 36f
private const val STANDBY_SIZE = 15f
private const val STANDBY_GAP = 10f
private const val WORDMARK_SIZE = 50f
private const val NO_CHANNELS_SIZE = 13f
private const val SPACING_EM = 0.13f

/** The slate for a show that is missing or will not play (design: board E2), with the "Up next" banner when known. */
@Composable
internal fun DifficultiesSlate(
    upNext: Banner?,
    channel: ChannelTag?,
) {
    Box(Modifier.fillMaxSize()) {
        TestCard(tvY = DIFFICULTIES_TV_Y) { card ->
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = card.dp(DIFFICULTIES_TOP))
                    .width(card.dp(DISC_TEXT_WIDTH))
                    .testTag(LiveTags.DIFFICULTIES),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.live_difficulties_title),
                    style = display(card.sp(TITLE_SIZE), card.sp(TITLE_LINE)),
                    color = BroadcastColors.Ink,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.offset(y = card.dp(STANDBY_GAP)))
                Text(
                    text = stringResource(R.string.live_difficulties_standby),
                    style = mono(card.sp(STANDBY_SIZE)),
                    color = BroadcastColors.Ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
        CardBanner(upNext, channel)
    }
}

/**
 * The StationBreak ident card: the test card with the icon's wordmark, for a gap in the schedule or a show that ended
 * early, with the "Up next" banner when known. F3.1's bumpers replace it later.
 */
@Composable
internal fun IdentSlate(
    upNext: Banner?,
    channel: ChannelTag?,
) {
    Box(Modifier.fillMaxSize()) {
        TestCard(tvY = IDENT_TV_Y) { card -> Wordmark(card, LiveTags.IDENT) }
        CardBanner(upNext, channel)
    }
}

/** The "up next" banner when known, else the channel's own strip (design: "the card shows the channel only"). */
@Composable
private fun CardBanner(
    upNext: Banner?,
    channel: ChannelTag?,
) {
    if (upNext != null) {
        CableBanner(upNext, cornerNumber = false)
    } else if (channel != null) {
        ChannelBanner(channel)
    }
}

/** Signed in, but no channel is on the air: the ident card with one line, and nothing else. */
@Composable
internal fun NoChannelsSlate() {
    Box(Modifier.fillMaxSize()) {
        TestCard(tvY = IDENT_TV_Y) { card ->
            Wordmark(card, LiveTags.IDENT)
            Text(
                text = stringResource(R.string.live_no_channels),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = card.dp(NO_CHANNELS_TOP))
                        .width(card.dp(NO_CHANNELS_WIDTH))
                        .testTag(LiveTags.NO_CHANNELS),
                style = mono(card.sp(NO_CHANNELS_SIZE)),
                color = BroadcastColors.Ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Wordmark(
    card: CardScale,
    tag: String,
) {
    Column(
        Modifier.align(Alignment.TopCenter).offset(y = card.dp(IDENT_TOP)).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = display(card.sp(WORDMARK_SIZE), card.sp(WORDMARK_SIZE))
        Text(stringResource(R.string.live_ident_station), style = style, color = BroadcastColors.Ink)
        Text(stringResource(R.string.live_ident_break), style = style, color = BroadcastColors.Ink)
    }
}

private fun display(
    size: androidx.compose.ui.unit.TextUnit,
    line: androidx.compose.ui.unit.TextUnit,
) = TextStyle(fontFamily = BroadcastType.Display, fontSize = size, lineHeight = line)

private fun mono(size: androidx.compose.ui.unit.TextUnit) =
    TextStyle(
        fontFamily = BroadcastType.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        letterSpacing = size * SPACING_EM,
    )
