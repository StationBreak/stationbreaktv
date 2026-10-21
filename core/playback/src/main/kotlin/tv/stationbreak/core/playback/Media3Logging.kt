// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Switches Media3's own logcat output off, in every build. Media3 logs player errors with their full cause chains, and
 * a cause can quote a playlist line that carries `api_key=<token>` (ADR-0016, 1). Our own events carry the
 * diagnostics instead: stable error code names and HTTP statuses. The player calls this before it builds ExoPlayer.
 */
@UnstableApi
internal fun silenceMedia3Logging() {
    Log.setLogLevel(Log.LOG_LEVEL_OFF)
}
