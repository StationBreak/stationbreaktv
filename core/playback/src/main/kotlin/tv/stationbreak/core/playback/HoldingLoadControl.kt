// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * [delegate], except that while [holding] is true it stops loading once [holdUs] of media is buffered, so a standby
 * player that waits for a flip does not fill a second full media buffer (the main one may take a quarter of the heap).
 * Every call [delegate] implements is forwarded explicitly.
 */
@UnstableApi
internal class HoldingLoadControl(
    private val delegate: LoadControl,
    private val holdUs: Long,
    private val holding: () -> Boolean,
) : LoadControl {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (holding() && parameters.bufferedDurationUs >= holdUs) false else delegate.shouldContinueLoading(parameters)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(id: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(id)

    override fun shouldStartPlayback(params: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(params)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}
