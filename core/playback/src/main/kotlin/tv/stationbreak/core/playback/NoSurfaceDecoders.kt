// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * Media3's own list of decoders that cannot run without an output surface (the Insignia, AFTJMST12, among them), read
 * through the renderer's protected `codecNeedsSetOutputSurfaceWorkaround`. A hidden standby cannot warm on these.
 */
@UnstableApi
internal object NoSurfaceDecoders {
    /**
     * True when the first decoder listed for [videoMimeType] is on the list. Throws when it cannot say (no decoder
     * listed, or the renderer or the codec list failed): the caller fails closed ([DeviceWarmth]).
     */
    fun listed(
        context: Context,
        videoMimeType: String = MimeTypes.VIDEO_H264,
    ): Boolean {
        val decoder =
            checkNotNull(MediaCodecSelector.DEFAULT.getDecoderInfos(videoMimeType, false, false).firstOrNull()) {
                "no decoder listed"
            }
        return Asker(context).asks(decoder.name)
    }

    /** Never prepared or enabled: only used to reach the protected list. */
    private class Asker(
        context: Context,
    ) : MediaCodecVideoRenderer(
            MediaCodecVideoRenderer.Builder(context).setMediaCodecSelector(MediaCodecSelector.DEFAULT),
        ) {
        fun asks(decoderName: String): Boolean = codecNeedsSetOutputSurfaceWorkaround(decoderName)
    }
}

/**
 * Whether this device may warm a standby, and why not: the reason names of the `disabled reason=` line. Checked in
 * this order:
 * 1. [DENYLIST]: the model (`Build.MODEL`, which is what Media3's own list matches AFTJMST12 against, and what the
 *    project's device notes call it) is on the app's [WarmConfig.warmDeviceDenylist]. Independent of Media3, so the
 *    Insignia stays off even if a Media3 upgrade dropped it from its list, and Media3 is not even asked there.
 * 2. [DECODER_MISSING]: the runtime probe found the decoder missing earlier in this process ([markDecoderMissing]).
 * 3. [DEVICE_LIST]: Media3's no-surface-decoder list ([NoSurfaceDecoders]).
 * 4. [DEVICE_CHECK]: Media3's list could not be read. This fails closed: a device that cannot be checked does not warm.
 *
 * Media3's answer and a missing decoder are kept for the life of the process ([of]): reading the list builds a
 * renderer, and the probe costs a whole standby, so each happens at most once per process. Thread-safe.
 */
internal class DeviceWarmth(
    private val model: () -> String?,
    private val media3Lists: () -> Boolean,
) {
    private val media3Reason: String? by lazy {
        runCatching { if (media3Lists()) DEVICE_LIST else null }.getOrElse { DEVICE_CHECK }
    }

    @Volatile private var decoderMissing = false

    /** The reason this device cannot warm under [denylist], or null when it can. */
    fun cannot(denylist: Set<String>): String? {
        val name = runCatching(model).getOrNull()
        return when {
            name != null && name in denylist -> DENYLIST
            decoderMissing -> DECODER_MISSING
            else -> media3Reason
        }
    }

    /** The runtime probe found no decoder behind a ready standby: no player of this process warms again. */
    fun markDecoderMissing() {
        decoderMissing = true
    }

    companion object {
        const val DENYLIST = "denylist"
        const val DECODER_MISSING = "decoderMissing"
        const val DEVICE_LIST = "deviceList"
        const val DEVICE_CHECK = "deviceCheck"

        @Volatile private var process: DeviceWarmth? = null

        /** The one of this process. It keeps the application context only (never an activity's). */
        @UnstableApi
        fun of(context: Context): DeviceWarmth {
            process?.let { return it }
            return synchronized(this) { process ?: fresh(context).also { process = it } }
        }

        /** A new one, with its own kept answers: for tests, which must not share the process's. */
        @UnstableApi
        fun fresh(context: Context): DeviceWarmth {
            val app = runCatching { context.applicationContext }.getOrNull() ?: context
            return DeviceWarmth({ Build.MODEL }, { NoSurfaceDecoders.listed(app) })
        }
    }
}
