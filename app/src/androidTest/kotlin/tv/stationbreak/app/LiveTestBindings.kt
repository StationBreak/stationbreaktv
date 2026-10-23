// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory

/**
 * The player factory for a navigation test that swaps the media server for a fake. The real one reads media through
 * the real HTTP client, which these tests replace. The live screen makes a player when it starts, before it knows the
 * lineup is empty, so the player must exist; it just never does anything. A test module that uninstalls
 * `MediaServerModule` also uninstalls `PlayerModule` and provides this.
 */
internal object LiveTestBindings {
    val players = VideoPlayerFactory { _, _ -> NoOpPlayer() }
}

/** A player that plays nothing and reports nothing. */
private class NoOpPlayer : VideoPlayer {
    override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()

    override fun play(source: PlayerSource): Long = 0

    override fun stop() = Unit

    override fun attach(surface: SurfaceView?) = Unit

    override fun release() = Unit
}
