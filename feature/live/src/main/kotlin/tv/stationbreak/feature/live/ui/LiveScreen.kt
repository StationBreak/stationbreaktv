// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramInfoOverlay
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import java.time.ZoneId

/**
 * What the live channel shows for [state] (spec 14). Stateless: the machine in `LiveChannel` decides, this draws.
 *
 * [surface] is the video surface and is always the bottom layer, composed in the same place for every state, so the
 * picture is there the moment the player has one. Everything else is drawn over it and opaque, except the banner while
 * the picture plays, so snow and slates never let a half-ready picture show through.
 *
 * [info] is the program info overlay (spec 17), over the picture in place of the banner; the actions banner's buttons
 * send their [LiveCommand] to [onCommand].
 *
 * [guide], while the program guide is open (spec 17), is drawn over the picture in place of everything else: the guide
 * is opaque, so a banner, a slate or animated snow under it would only cost frames. The picture keeps playing.
 */
@Composable
internal fun LiveScreen(
    state: LiveState,
    info: LiveInfo? = null,
    onCommand: (LiveCommand) -> Unit = {},
    guide: (@Composable () -> Unit)? = null,
    surface: @Composable () -> Unit,
) {
    // The frames are made off the main thread the first time the screen is up, ready before the first tune needs them.
    LaunchedEffect(Unit) { SnowFrames.get() }
    Box(Modifier.fillMaxSize().background(BroadcastColors.Ink)) {
        surface()
        if (guide != null) {
            guide()
        } else {
            LiveLayers(state, info, onCommand)
        }
    }
}

/** What live TV draws over its picture for [state]: snow, the banners, the slates and the info overlay. */
@Composable
private fun LiveLayers(
    state: LiveState,
    info: LiveInfo?,
    onCommand: (LiveCommand) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            is LiveState.Tuning -> {
                Snow()
                CableBanner(state.banner)
            }

            is LiveState.Watching -> {
                state.banner?.let {
                    CableBanner(
                        it,
                        onInfo = { onCommand(LiveCommand.ShowInfo) },
                        onLast = { onCommand(LiveCommand.Previous) },
                    )
                }
            }

            is LiveState.Choosing -> {
                // The number moving (spec 15): the old picture shows through untouched, or snow when there is none.
                if (!state.overPicture) Snow()
                Box(Modifier.fillMaxSize().testTag(LiveTags.BANNER_CHOOSING)) { ChannelBanner(state.target) }
            }

            is LiveState.TechnicalDifficulties -> {
                DifficultiesSlate(state.upNext, state.channel)
            }

            is LiveState.Ident -> {
                IdentSlate(state.upNext, state.channel)
            }

            LiveState.NoChannels -> {
                NoChannelsSlate()
            }

            LiveState.SignedOut -> {
                // Handing over to sign-in: nothing to show but the ground.
                Box(Modifier.fillMaxSize())
            }
        }
        info?.let { InfoPanel(it) }
    }
}

/** [info] as the shared overlay: the banner's own words, and what the server added once it has. */
@Composable
private fun InfoPanel(info: LiveInfo) {
    val context = LocalContext.current
    val copy = remember(context) { ProgramCopy.from(context.resources) }
    val is24h =
        remember(context) {
            android.text.format.DateFormat
                .is24HourFormat(context)
        }
    val banner = info.banner
    val slot =
        remember(banner, is24h) {
            ProgramText.slot(banner.slotStartMs, banner.slotEndMs, ZoneId.systemDefault(), is24h)
        }
    val episode = remember(banner) { BannerText.episodeLine(banner.info, copy) }
    val details = info.details
    ProgramInfoOverlay(
        channel = channelLine(banner.number, banner.channelName),
        title = banner.title.orEmpty(),
        episodeLine = episode,
        slot = slot,
        overview = details?.overview,
        year = details?.year,
        genres = details?.genres.orEmpty(),
        runtimeMinutes = details?.runtime?.inWholeMinutes?.toInt(),
        loading = info.loading,
        modifier = Modifier.testTag(LiveTags.INFO),
    )
}
