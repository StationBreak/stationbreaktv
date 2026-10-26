// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * When the audio and video start-up steps of one play happened, in ms since the play call, each [UNKNOWN] when it did
 * not happen before the play reached Playing (diagnostics only, #392). Numbers only. A -1 may also mean the decoder or
 * AudioTrack was reused, so no init event came, not that the step was slow. [playCallMs] is the play call's offset
 * from the tune's start, set by the tune controller: add it to a step to compare with the tune's first frame and
 * playing times, which run from the tune's start. The decoder `Dur` fields are the
 * time the decoder took to initialise. [audioUnderruns] counts the audio underruns before Playing.
 */
data class AvTimings(
    val playCallMs: Long = UNKNOWN,
    val videoDecInitMs: Long = UNKNOWN,
    val videoDecInitDurMs: Long = UNKNOWN,
    val audioDecInitMs: Long = UNKNOWN,
    val audioDecInitDurMs: Long = UNKNOWN,
    val audioTrackInitMs: Long = UNKNOWN,
    val audioAdvancingMs: Long = UNKNOWN,
    val audioUnderruns: Long = 0,
    val readyMs: Long = UNKNOWN,
) {
    companion object {
        /** A step that did not happen before Playing. */
        const val UNKNOWN = -1L
    }
}

/**
 * Records the [AvTimings] of the play now on the player. [begin] starts a play at its call time; each record call
 * carries the generation of the event's play and is ignored when it is not the current one, or once [finish] has
 * closed the window at Playing. The first time of each step counts. Used on the player's looper.
 */
internal class AvRecorder {
    private var generation = NONE
    private var startedAt = 0L
    private var open = false
    private var timings = AvTimings()

    fun begin(
        generation: Long,
        calledAt: Long,
    ) {
        this.generation = generation
        startedAt = calledAt
        open = true
        timings = AvTimings()
    }

    fun videoDecoder(
        generation: Long,
        at: Long,
        initMs: Long,
    ) = record(generation) {
        if (it.videoDecInitMs == AvTimings.UNKNOWN) {
            it.copy(videoDecInitMs = at - startedAt, videoDecInitDurMs = initMs)
        } else {
            it
        }
    }

    fun audioDecoder(
        generation: Long,
        at: Long,
        initMs: Long,
    ) = record(generation) {
        if (it.audioDecInitMs == AvTimings.UNKNOWN) {
            it.copy(audioDecInitMs = at - startedAt, audioDecInitDurMs = initMs)
        } else {
            it
        }
    }

    fun audioTrack(
        generation: Long,
        at: Long,
    ) = record(generation) {
        if (it.audioTrackInitMs == AvTimings.UNKNOWN) it.copy(audioTrackInitMs = at - startedAt) else it
    }

    fun audioAdvancing(
        generation: Long,
        at: Long,
    ) = record(generation) {
        if (it.audioAdvancingMs == AvTimings.UNKNOWN) it.copy(audioAdvancingMs = at - startedAt) else it
    }

    fun underrun(generation: Long) = record(generation) { it.copy(audioUnderruns = it.audioUnderruns + 1) }

    fun ready(
        generation: Long,
        at: Long,
    ) = record(generation) {
        if (it.readyMs == AvTimings.UNKNOWN) it.copy(readyMs = at - startedAt) else it
    }

    /** Closes the window at Playing of [generation] and returns what was recorded; null for another play's Playing. */
    fun finish(generation: Long): AvTimings? {
        if (generation != this.generation) return null
        open = false
        return timings
    }

    private fun record(
        generation: Long,
        change: (AvTimings) -> AvTimings,
    ) {
        if (open && generation == this.generation) timings = change(timings)
    }

    private companion object {
        const val NONE = -1L
    }
}
