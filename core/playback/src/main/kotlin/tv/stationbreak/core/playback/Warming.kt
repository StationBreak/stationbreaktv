// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.os.Debug
import android.util.Log
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Spike #563 (warm decoder): the switch and constants of keeping the Down neighbour's media prepared on a second,
 * paused player with its video decoder already running, so a flip onto it is a surface hand-over.
 *
 * The switch is on by default. A device run turns it off without a new APK:
 * `adb shell setprop log.tag.SBWARM ASSERT` (back on: `adb shell setprop log.tag.SBWARM INFO`). It is read when a
 * neighbour is pre-resolved, so it takes effect for the next neighbour prepared; turning it off also frees the standby
 * player at that point.
 *
 * A second switch, off by default, keeps the standby's audio enabled (muted) too:
 * `adb shell setprop log.tag.SBWARMAUD DEBUG` (back off: `adb shell setprop log.tag.SBWARMAUD INFO`). Off, the audio
 * renderer starts at the swap; on, the standby also opens an audio track, which a passthrough audio output may refuse.
 */
object WarmSettings {
    /** The system property tag of the main switch (an Android log tag, 23 characters at most). */
    const val SWITCH_TAG = "SBWARM"

    /** The system property tag of the audio switch. */
    const val AUDIO_TAG = "SBWARMAUD"

    /** How long the standby may take from its prepare to ready (decoder up, first frame) before it is given up. */
    const val READY_TIMEOUT_MS = 15_000L

    /**
     * Once the player is ready, how long to wait for the video decoder before deciding the device cannot decode
     * without a surface (Media3 then never starts the codec on a hidden player; see the report).
     */
    const val DECODER_GRACE_MS = 1_500L

    /** How much media the standby buffers while it waits (it is discarded by the seek at the swap). */
    const val HOLD_BUFFER_MS = 2_000L

    /** At the swap, a standby this close to the live position is not seeked. */
    const val SEEK_TOLERANCE_MS = 250L

    /** True unless the switch is turned off. Never throws; where the log service is unavailable it reads as off. */
    fun enabled(): Boolean = runCatching { Log.isLoggable(SWITCH_TAG, Log.WARN) }.getOrDefault(false)

    /** True only when the audio switch is turned on (DEBUG or lower). */
    fun audioWarm(): Boolean = runCatching { Log.isLoggable(AUDIO_TAG, Log.DEBUG) }.getOrDefault(false)
}

/** The `SBWARM` log lines of the spike: numbers, fixed names and the preload key only. */
object WarmLog {
    const val TAG = "SBWARM"
    private const val KIB = 1_024L

    fun line(text: String) {
        runCatching { Log.i(TAG, text) }
    }

    /** The memory now, as fields: Java heap in use and its limit, and the native heap allocated, in KiB. */
    fun memoryFields(): String {
        val runtime = Runtime.getRuntime()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        return "javaKb=${(runtime.totalMemory() - runtime.freeMemory()) / KIB} " +
            "javaMaxKb=${runtime.maxMemory() / KIB} nativeKb=${native / KIB}"
    }
}

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
