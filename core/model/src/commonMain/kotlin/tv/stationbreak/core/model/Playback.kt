// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

/** One playable version of an item. The server's file path is deliberately not here (R2). */
data class MediaSource(
    val id: MediaSourceId,
    val name: String?,
    val container: String?,
    val runtime: Duration?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
)

/**
 * Builds URLs a player can fetch. Every URL is made from IDs and the server's base address and carries no credential:
 * not in the query, not as userinfo (R2). Authentication travels in the `Authorization` header of the shared HTTP
 * client, and which component adds it is settled by F0.5/F1.x.
 */
fun interface StreamUrls {
    /** The server's original file for [source], as a direct stream. */
    fun directStream(source: MediaSourceId): String
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
