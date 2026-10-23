// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

/** Which kind of track a [MediaStreamInfo] is. */
enum class StreamKind { VIDEO, AUDIO, SUBTITLE }

/** How the server would deliver a subtitle. Taken from the server's delivery method, never from a URL it supplied. */
enum class SubtitleDelivery { IN_STREAM, SEPARATE, BURNED_IN, NONE }

/**
 * One track of a [MediaSource]. [index] is the server's own stream index, the number to send back when choosing a
 * track. [delivery] is set for subtitle streams only. [positionInKind] is the stream's 0-based position among the
 * file's embedded streams of its own [kind] (the third audio track is 2), or null for an external subtitle, which is
 * not in the file. A player that numbers tracks per kind (Media3's track groups on direct play) maps [index] to its
 * own track with it. [videoProfile] (a [Codecs] profile name), [videoLevel] (level_idc) and [bitDepth] describe a video
 * stream and are null when the server did not say; the route oracle skips a limit it has no fact for.
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
    val positionInKind: Int? = null,
    val videoProfile: String? = null,
    val videoLevel: Int? = null,
    val bitDepth: Int? = null,
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
 * client, which the app hands to the player as `mediaCallFactory` (ADR-0016). The URLs the server's playlists in turn
 * contain may carry an `api_key`; that factory strips it.
 */
interface StreamUrls {
    /** The server's original file for [source], as stored (Jellyfin "direct play"). */
    fun original(source: MediaSourceId): String

    /**
     * The `master.m3u8` for [source]; the server may copy streams where it agrees. The tracks are the choice the
     * server resolved, not the player's wish: [audioIndex] is the audio stream to carry, and when null it is the
     * source's own [MediaSource.defaultAudioIndex], which is where the user's Jellyfin preference ended up. [burnIn] is
     * the index of a subtitle stream to burn into the picture, or null. A text subtitle never travels in this URL,
     * embedded or not: the caller fetches it as WebVTT from [subtitle], treating an embedded one as
     * [SubtitleDelivery.SEPARATE] on an HLS route (the server extracts it; ADR-0016, update 2026-10-22).
     * [limits] are the device's, [forceTranscode] stops the video being copied.
     * There is no start: the playlist covers the whole runtime from 0 and the player seeks (the server rejects
     * `StartTimeTicks` on segment requests, Jellyfin 12.2; ADR-0016).
     */
    @Suppress("LongParameterList") // Each is one independent fact the URL carries; a holder would only rename them.
    fun hls(
        source: MediaSource,
        audioIndex: Int?,
        burnIn: Int?,
        limits: DeviceCapabilities,
        forceTranscode: Boolean,
    ): String

    /** WebVTT for a text [stream] whose delivery is [SubtitleDelivery.SEPARATE], else null. Made from IDs, no token. */
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
