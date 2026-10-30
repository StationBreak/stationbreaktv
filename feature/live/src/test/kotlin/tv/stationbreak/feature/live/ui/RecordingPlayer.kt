// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A [VideoPlayer] that only writes down what it was asked to do, in order. */
internal class RecordingPlayer : VideoPlayer {
    val log = mutableListOf<String>()
    override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()

    override fun play(source: PlayerSource): Long {
        log += "play"
        return 1
    }

    override fun stop() {
        log += "stop"
    }

    override fun attach(surface: SurfaceView?) {
        log += if (surface == null) "attach(null)" else "attach(surface)"
    }

    override fun pause(generation: Long) {
        log += "pause:$generation"
    }

    override fun resume(generation: Long) {
        log += "resume:$generation"
    }

    override fun seekTo(
        generation: Long,
        position: Duration,
    ) {
        log += "seek:$generation:${position.inWholeMilliseconds}"
    }

    override fun position(generation: Long): Duration? = 1.seconds

    override fun seekable(generation: Long): Boolean = true

    override fun release() {
        log += "release"
    }
}
