// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * Media3's own list of decoders that cannot run without an output surface (the Insignia, AFTJMST12), read through
 * the renderer's protected `codecNeedsSetOutputSurfaceWorkaround`. A hidden standby cannot warm on these. This is the
 * first guard; the runtime probe is the second.
 */
@UnstableApi
internal object NoSurfaceDecoders {
    /** True when the first decoder listed for [videoMimeType] is on the list; false (no opinion) on any failure. */
    fun deviceCannot(
        context: Context,
        videoMimeType: String = MimeTypes.VIDEO_H264,
    ): Boolean =
        runCatching {
            val decoder =
                MediaCodecSelector.DEFAULT
                    .getDecoderInfos(videoMimeType, false, false)
                    .firstOrNull()
                    ?.name
            decoder != null && Asker(context).asks(decoder)
        }.getOrDefault(false)

    /** Never prepared or enabled: only used to reach the protected list. */
    private class Asker(
        context: Context,
    ) : MediaCodecVideoRenderer(
            MediaCodecVideoRenderer.Builder(context).setMediaCodecSelector(MediaCodecSelector.DEFAULT),
        ) {
        fun asks(decoderName: String): Boolean = codecNeedsSetOutputSurfaceWorkaround(decoderName)
    }
}
