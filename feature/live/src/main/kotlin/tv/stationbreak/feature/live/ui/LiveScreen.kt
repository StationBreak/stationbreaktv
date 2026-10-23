// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.feature.live.LiveState

/**
 * What the live channel shows for [state] (spec 14). Stateless: the machine in `LiveChannel` decides, this draws.
 *
 * [surface] is the video surface and is always the bottom layer, composed in the same place for every state, so the
 * picture is there the moment the player has one. Everything else is drawn over it and opaque, except the banner while
 * the picture plays, so snow and slates never let a half-ready picture show through.
 */
@Composable
internal fun LiveScreen(
    state: LiveState,
    surface: @Composable () -> Unit,
) {
    // The frames are made off the main thread the first time the screen is up, ready before the first tune needs them.
    LaunchedEffect(Unit) { SnowFrames.get() }
    Box(Modifier.fillMaxSize().background(BroadcastColors.Ink)) {
        surface()
        when (state) {
            is LiveState.Tuning -> {
                Snow()
                CableBanner(state.banner)
            }

            is LiveState.Watching -> {
                state.banner?.let { CableBanner(it) }
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
    }
}
