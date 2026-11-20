// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long setup's cheer may stay over live TV waiting for its picture. Past it, live TV's own stand-by shows, as it
 * did before the hand-over: a bounded wait, like the cheer's own hold, so a slow tune never leaves the cheer up.
 */
internal val SETUP_HAND_OVER_WAIT: Duration = 5.seconds

/**
 * The hand-over from setup's cheer ("WE'RE ON THE AIR!") to live TV, so the first session ends on a programme and not
 * on a stand-by card. While [covering], the graph draws the cheer's still over live TV, which is built and tunes
 * beneath it.
 *
 * [begin] when the cheer ends, in the same callback as the navigation to live TV: the still is composed in the frame
 * that builds live TV, so that heavy frame shows a still picture and drops nothing that moves. [hold] then waits for
 * [end] (live TV stopped standing by, or a key) for [wait] at most, and lifts the still either way. Live TV not entered
 * from the cheer is never covered: [end] alone does nothing.
 *
 * The hold deliberately does not survive a recreation of the graph (process death; the activity keeps itself across
 * configuration changes): it is in memory only, and a recreated graph shows live TV as it is, with its own stand-by
 * card if it is still tuning. The navigation is never left half done, since it happens with [begin].
 */
@Stable
internal class SetupHandOver(
    private val wait: Duration = SETUP_HAND_OVER_WAIT,
) {
    /** True while the cheer's still covers live TV. */
    var covering by mutableStateOf(false)
        private set

    private var over: CompletableDeferred<Unit>? = null

    /** The cheer has ended: its still covers what comes next. */
    fun begin() {
        over = CompletableDeferred()
        covering = true
    }

    /** Live TV stopped standing by, or a key was pressed: the still lifts. Nothing when it is not up. */
    fun end() {
        over?.complete(Unit)
        covering = false
    }

    /** Waits for [end] for [wait] at most, then lifts the still: after that, whatever live TV shows is seen. */
    suspend fun hold() {
        over?.let { withTimeoutOrNull(wait) { it.await() } }
        covering = false
    }
}
