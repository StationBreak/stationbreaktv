// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** How far down a cause chain [playerFailure] looks: a cause cycle cannot make it loop. */
private const val MAX_CAUSES = 16

/**
 * The event for a Media3 error: its stable error code name and, when a media load was answered with an HTTP error,
 * the status. Never a class name, a message or a URL: those may quote a playlist line with a token.
 */
@UnstableApi
internal fun playerFailure(
    error: PlaybackException,
    generation: Long,
    atMs: Long = PlayerEvent.NOT_KNOWN,
    positionMs: Long = PlayerEvent.NOT_KNOWN,
    bufferedMs: Long = PlayerEvent.NOT_KNOWN,
): PlayerEvent.Failed {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES)
    val status = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return PlayerEvent.Failed(error.errorCodeName, status, generation, atMs, positionMs, bufferedMs)
}
