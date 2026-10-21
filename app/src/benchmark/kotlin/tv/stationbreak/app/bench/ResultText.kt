// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.Route

// The words a result line uses for a file's tracks and for the route. Plain facts: no ID, title or URL.

internal fun isImage(stream: MediaStreamInfo): Boolean {
    val burnedIn = stream.delivery == SubtitleDelivery.BURNED_IN
    return burnedIn || Codecs.isImageSubtitle(stream.codec)
}

internal fun subtitleClass(chosen: MediaStreamInfo?): String =
    when {
        chosen == null -> "none"
        isImage(chosen) -> "image"
        chosen.delivery == SubtitleDelivery.SEPARATE -> "text-separate"
        else -> "text-in-stream"
    }

internal fun describeVideo(stream: MediaStreamInfo): String =
    listOfNotNull(
        stream.codec?.lowercase() ?: "?",
        if (stream.width != null && stream.height != null) "${stream.width}x${stream.height}" else null,
        stream.bitDepth?.let { "${it}bit" },
    ).joinToString(" ")

internal fun describeAudio(stream: MediaStreamInfo): String =
    listOfNotNull(stream.codec?.lowercase() ?: "?", stream.channels?.let { "${it}ch" }).joinToString(" ")

internal fun Route.wire(): String =
    when (this) {
        Route.DIRECT_PLAY -> "direct-play"
        Route.DIRECT_STREAM -> "direct-stream"
        Route.TRANSCODE -> "transcode"
    }
