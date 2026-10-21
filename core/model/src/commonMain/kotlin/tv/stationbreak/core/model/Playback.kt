// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

/** Which kind of track a [MediaStreamInfo] is. */
enum class StreamKind { VIDEO, AUDIO, SUBTITLE }

/** How the server would deliver a subtitle. Taken from the server's delivery method, never from a URL it supplied. */
enum class SubtitleDelivery { IN_STREAM, SEPARATE, BURNED_IN, NONE }

/**
 * One track of a [MediaSource]. [index] is the server's own stream index, the number to send back when choosing a
 * track. [delivery] is set for subtitle streams only.
 */
data class MediaStreamInfo(
    val index: Int,
    val kind: StreamKind,
    val codec: String?,
    val language: String?,
    val channels: Int?,
    val width: Int?,
    val height: Int?,
    val isExternal: Boolean,
    val isDefault: Boolean,
    val isForced: Boolean,
    val delivery: SubtitleDelivery?,
)

/** One playable version of an item. The server's file path is deliberately not here (R2). */
data class MediaSource(
    val id: MediaSourceId,
    val name: String?,
    val container: String?,
    val runtime: Duration?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean = false,
    val streams: List<MediaStreamInfo> = emptyList(),
    val defaultAudioIndex: Int? = null,
    val defaultSubtitleIndex: Int? = null,
    val bitrate: Int? = null,
    val transcodeReasons: Set<String> = emptySet(),
)

/**
 * How a player wants to start: the device [capabilities], where to [start], and which tracks. A null [audioIndex] or
 * [subtitleIndex] lets the server apply the user's preferences. [forceTranscode] asks for a transcode even where the
 * file could be played as stored.
 */
data class PlaybackRequest(
    val capabilities: DeviceCapabilities,
    val start: Duration,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val forceTranscode: Boolean,
) {
    init {
        require(!start.isNegative()) { "start must not be negative" }
    }
}

/**
 * Builds URLs a player can fetch. Every URL is made from IDs and the server's base address and carries no credential:
 * not in the query, not as userinfo (R2). Authentication travels in the `Authorization` header of the shared HTTP
 * client, and which component adds it is settled by F0.5/F1.x.
 */
interface StreamUrls {
    /** The server's original file for [source], as stored (Jellyfin "direct play"). */
    fun original(source: MediaSourceId): String

    /**
     * The `master.m3u8` for [source] under [request]; the server may copy streams where it agrees. [burnIn] is the
     * index of a subtitle stream to burn into the picture, or null.
     */
    fun hls(
        source: MediaSourceId,
        request: PlaybackRequest,
        burnIn: Int?,
    ): String

    /** WebVTT for a text [stream] delivered [SubtitleDelivery.SEPARATE], else null. */
    fun subtitle(
        source: MediaSourceId,
        stream: MediaStreamInfo,
    ): String?
}

/**
 * What a player needs to start [itemId]: its [sources], the [playSessionId] to report under, and the [streams] to
 * build URLs from. Nothing the server supplied as a URL or a path is kept. [toString] shows no URL.
 */
class PlaybackInfo(
    val itemId: ItemId,
    val playSessionId: PlaySessionId?,
    val sources: List<MediaSource>,
    val streams: StreamUrls,
) {
    override fun toString(): String = "PlaybackInfo(item=$itemId, sources=${sources.size}, streams=<urls not shown>)"
}

/**
 * One playback progress report. [position] is how far in the player is; the adapter turns it into the server's units.
 * What to report and how often (about every 10 s, the played threshold) is S1.5.1's decision.
 */
data class PlaybackReport(
    val itemId: ItemId,
    val sourceId: MediaSourceId,
    val playSessionId: PlaySessionId?,
    val position: Duration,
    val isPaused: Boolean = false,
)
