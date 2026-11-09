// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long one [LibrarySelection.change] took, in milliseconds, by step: [waitMs] for the selection's lock (and the
 * catch-up of a cache left behind); [turnWaitMs] from asking for the repairer's turn until it was held (the cache's
 * floor read just before, the move to the repairer's thread and the wait for the turn); [recordMs] in that turn
 * without the save (today's repair and its record); [saveMs] in the settings save; [cacheMs] in the steps after the
 * save (the library cache's write, the add pass asked for, the new choice published); and [totalMs] for the whole
 * call. A stale attempt that ran again adds to its steps. Numbers only: never an ID, a name or a count.
 */
data class ChangeTiming(
    val waitMs: Long,
    val turnWaitMs: Long,
    val recordMs: Long,
    val saveMs: Long,
    val cacheMs: Long,
    val totalMs: Long,
) {
    /** The line the app logs. */
    fun line(): String =
        "SelectionChangeTook wait=$waitMs turnWait=$turnWaitMs record=$recordMs save=$saveMs cache=$cacheMs " +
            "total=$totalMs"
}

/** The steps of one change as it runs, on [ticks]: each adds what it took. Used under the selection's lock only. */
internal class ChangeSteps(
    private val ticks: TimeSource,
) {
    private val began = ticks.markNow()
    private var waitMs = 0L
    private var allocateMs = 0L
    private var turnWaitMs = 0L
    private var saveMs = 0L
    private var cacheMs = 0L

    // Set while the repairer's turn is asked for and not yet held.
    private var asked: TimeMark? = null

    fun locked() {
        waitMs = began.elapsedNow().inWholeMilliseconds
    }

    // The allocation: the repairer's turn is asked for from its start.
    suspend fun <T> allocating(block: suspend () -> T): T {
        asked = ticks.markNow()
        return timed({ allocateMs += it }, block).also { asked = null }
    }

    // The repairer's turn is held now.
    fun turnHeld() {
        asked?.let { turnWaitMs += it.elapsedNow().inWholeMilliseconds }
        asked = null
    }

    suspend fun <T> saving(block: suspend () -> T): T = timed({ saveMs += it }, block)

    suspend fun <T> caching(block: suspend () -> T): T = timed({ cacheMs += it }, block)

    fun timing(): ChangeTiming =
        ChangeTiming(
            waitMs = waitMs,
            turnWaitMs = turnWaitMs,
            recordMs = (allocateMs - turnWaitMs - saveMs).coerceAtLeast(0L),
            saveMs = saveMs,
            cacheMs = cacheMs,
            totalMs = began.elapsedNow().inWholeMilliseconds,
        )

    private suspend fun <T> timed(
        add: (Long) -> Unit,
        block: suspend () -> T,
    ): T {
        val mark = ticks.markNow()
        try {
            return block()
        } finally {
            add(mark.elapsedNow().inWholeMilliseconds)
        }
    }
}
