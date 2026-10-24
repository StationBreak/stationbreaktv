// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the live screen shows: the sitting's own state, with [LiveState.Choosing] laid over it while the viewer moves
 * the channel number (spec 15). The sitting keeps writing [value] as it follows its channel (a boundary cut, the banner
 * fading) and never knows about the overlay; while one is up, those writes are kept and not published, and [drop]
 * brings the latest back. One lock covers both sides, since the sitting and the flipping run as separate coroutines.
 */
internal class Screen(
    initial: LiveState,
) {
    private val out = MutableStateFlow(initial)
    private var shown = initial
    private var choosing: LiveState.Choosing? = null
    private var committed = false

    // After a commit the old picture is gone (the tuner stops) until the new sitting writes its first state.
    private var stale = false
    private var handedOver = false

    /** What the screen shows, [LiveState.Choosing] included. */
    val flow: StateFlow<LiveState> = out.asStateFlow()

    /** The sitting's state, which a [LiveState.Choosing] hides. Written only by the sitting. */
    var value: LiveState
        get() = synchronized(this) { shown }
        set(next) =
            synchronized(this) {
                shown = next
                if (handedOver) {
                    stale = false
                    handedOver = false
                }
                if (choosing == null) out.value = next
            }

    /** True only while the sitting's picture is up: it is Watching, and no commit is waiting on the new sitting. */
    val pictureUp: Boolean get() = synchronized(this) { !stale && shown is LiveState.Watching }

    fun compareAndSet(
        expect: LiveState,
        update: LiveState,
    ): Boolean =
        synchronized(this) {
            if (shown != expect) return false
            value = update
            true
        }

    /** The number moves: show [state] over whatever the sitting shows. A press replaces a commit's hold. */
    fun choose(state: LiveState.Choosing) =
        synchronized(this) {
            choosing = state
            committed = false
            out.value = state
        }

    /** A commit: keep the number up, over snow, until the new sitting's first state replaces it. */
    fun commit(state: LiveState.Choosing) =
        synchronized(this) {
            choosing = state
            committed = true
            stale = true
            handedOver = false
            out.value = state
        }

    /** The new sitting has started: its next write replaces a commit's hold. A newer press is left alone. */
    fun handOver() =
        synchronized(this) {
            if (stale) handedOver = true
            if (committed) {
                choosing = null
                committed = false
            }
        }

    /** Back to what the sitting shows (a full wrap, or the end of the run). */
    fun drop() =
        synchronized(this) {
            choosing = null
            committed = false
            out.value = shown
        }
}
