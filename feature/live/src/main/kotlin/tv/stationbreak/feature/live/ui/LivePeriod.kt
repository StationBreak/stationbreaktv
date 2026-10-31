// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.feature.live.Kept
import tv.stationbreak.feature.live.LiveChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * One STARTED period's player and machine, and the way out of it that cannot wait (#431). [leave] runs on the caller's
 * thread, inside the lifecycle callback that left: it ends the [machine]'s reporting (its Stopped is queued at once),
 * stops, detaches and releases the player, and cancels the [sitting]. The suspending teardown (the tuner's stop and
 * close, which wait on the server for the encode stop) follows when the sitting unwinds, after the player is gone.
 *
 * Before any of that, [keep] is given the [machine]'s paused place ([LiveChannel.kept]; null unless paused), or, with
 * no machine yet, the [resume] place this period was given, so the ViewModel can hold it across the stop.
 *
 * [player] is what the tuner is given: once left, every call on it is dropped, so a tune still unwinding, the tuner's
 * own stop and the host's release can neither start sound again nor reach the released player. A second [leave] does
 * nothing.
 */
internal class LivePeriod(
    private val inner: VideoPlayer,
    private val sitting: Job,
    /** The kept place this period's run was given, if any: kept again when the period is left before its machine. */
    private val resume: Kept?,
    /** Told the paused place to keep, once, as the period is left. */
    private val keep: (Kept?) -> Unit,
) {
    private val left = AtomicBoolean(false)

    /** The machine of this period, once there is one: [leave] ends its reporting. */
    @Volatile
    var machine: LiveChannel? = null

    val player: VideoPlayer = Guarded()

    /**
     * Leaves the period; true when this call did, false when it was already left (or released). Main thread only: the
     * check, the read of the kept place and the set of the left flag are not one atomic step.
     */
    fun leave(): Boolean {
        if (left.get()) return false
        // Read before the teardown (ADR-0021, amendment to ADR-0017 6): a paused place survives the stop. With no
        // machine yet, the place this period was given is still the one untended pause.
        val running = machine
        keep(if (running == null) resume?.copy(untended = true) else running.kept())
        val leaving = left.compareAndSet(false, true)
        if (leaving) {
            machine?.leave()
            inner.stop()
            inner.attach(null)
            inner.release()
            sitting.cancel()
        }
        return leaving
    }

    private inner class Guarded : VideoPlayer {
        override val events: SharedFlow<PlayerEvent> get() = inner.events

        // A play after the leave is dropped: its generation is one no event ever carries.
        override fun play(source: PlayerSource): Long = if (left.get()) NO_GENERATION else inner.play(source)

        override fun stop() {
            if (!left.get()) inner.stop()
        }

        override fun pause(generation: Long) {
            if (!left.get()) inner.pause(generation)
        }

        override fun resume(generation: Long) {
            if (!left.get()) inner.resume(generation)
        }

        override fun seekTo(
            generation: Long,
            position: Duration,
        ) {
            if (!left.get()) inner.seekTo(generation, position)
        }

        override fun position(generation: Long): Duration? = if (left.get()) null else inner.position(generation)

        override suspend fun currentPosition(generation: Long): Duration? {
            if (left.get()) return null
            return inner.currentPosition(generation)
        }

        override fun seekable(generation: Long): Boolean = !left.get() && inner.seekable(generation)

        override fun attach(surface: SurfaceView?) {
            if (!left.get()) inner.attach(surface)
        }

        override fun release() {
            if (left.compareAndSet(false, true)) inner.release()
        }
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}
