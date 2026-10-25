// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/**
 * How a host gets a [VideoPlayer] without naming a Media3 type: `app` binds one (with the credentialed data source
 * behind it), and a screen injects it. The host creates one player for its lifetime. Teardown order is
 * close, then release: it calls `TuneController.close` and waits for it, and only then [VideoPlayer.release]
 * (ADR-0016, 5).
 */
fun interface VideoPlayerFactory {
    /**
     * Builds a player. Call it on the thread whose looper should own the player (the main thread). [clock] stamps the
     * player's events.
     */
    fun create(
        context: Context,
        clock: () -> Long,
    ): VideoPlayer
}

/**
 * The [VideoPlayerFactory] for Media3: every player it builds reads media through [dataSource] and buffers as
 * [buffering] says (the safe defaults if app DI supplies none).
 */
@UnstableApi
class Media3VideoPlayerFactory(
    private val dataSource: DataSource.Factory,
    private val buffering: PlaybackBuffering = PlaybackBuffering(),
) : VideoPlayerFactory {
    override fun create(
        context: Context,
        clock: () -> Long,
    ): VideoPlayer = Media3VideoPlayer(context, dataSource, clock, buffering)
}
