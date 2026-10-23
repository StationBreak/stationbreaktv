// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import tv.stationbreak.core.playback.VideoPlayerFactory

/**
 * The player factory for a navigation test that swaps the media server for a fake. The real one reads media through
 * the real HTTP client, which these tests replace, and no navigation test plays anything: the lineup is empty, so the
 * live screen shows "No channels on the air yet". A test module that uninstalls `MediaServerModule` also uninstalls
 * `PlayerModule` and provides this.
 */
internal object LiveTestBindings {
    /** A factory whose player is never made: a screen that tries to play in these tests is a bug. */
    val players = VideoPlayerFactory { _, _ -> error("The navigation tests play nothing") }
}
