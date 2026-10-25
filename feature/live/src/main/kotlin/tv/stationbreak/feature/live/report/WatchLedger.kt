// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One airing of one item: the block of [channelId] that started at [blockStartMs]. A retry of the same block is the
 * same airing; a back-to-back repeat of the item is a new block, so a new airing.
 */
data class AiringKey(
    val channelId: ChannelId,
    val blockStartMs: Long,
    val itemId: ItemId,
)

/**
 * What the viewer actually watched, per airing, for the life of one `LiveChannel.run` (spec 16, R3/R4/R5/R7). Pure:
 * every method is told the time, so virtual time drives it. Not thread-safe; the reporter uses it from one coroutine.
 *
 * Watched time is the time an airing is open minus its stalls; it adds up across flips away and back.
 */
internal class WatchLedger(
    private val config: ReportingConfig,
) {
    private class Entry {
        var watched: Duration = Duration.ZERO
        var marked = false
        var markFailures = 0
    }

    private val entries = mutableMapOf<AiringKey, Entry>()
    private var sittingId: Long? = null
    private var sittingStart: Instant? = null
    private var open: AiringKey? = null

    /** When the open airing's current counting span began; null while it is stalled or none is open. */
    private var since: Instant? = null

    fun sittingStarted(
        id: Long,
        at: Instant,
    ) {
        sittingId = id
        sittingStart = at
    }

    /**
     * How long to wait before reporting an item that just began in sitting [id] (R4): none once the sitting is older
     * than `startAfter`, the full `startAfter` for a fresh tune or flip.
     */
    fun dwell(
        id: Long,
        at: Instant,
    ): Duration {
        val start = sittingStart
        val settled = sittingId == id && start != null && at - start > config.startAfter
        return if (settled) Duration.ZERO else config.startAfter
    }

    /** Starts counting [key]; any airing still open is closed first. */
    fun enter(
        key: AiringKey,
        at: Instant,
    ) {
        leave(at)
        entry(key)
        open = key
        since = at
    }

    fun stall(at: Instant) {
        val key = open ?: return
        val from = since ?: return
        entry(key).watched += (at - from).coerceAtLeast(Duration.ZERO)
        since = null
    }

    fun resumed(at: Instant) {
        if (open != null && since == null) since = at
    }

    /** Closes the open airing; an open stall is simply not counted. */
    fun leave(at: Instant) {
        stall(at)
        open = null
        since = null
    }

    fun watched(
        key: AiringKey,
        at: Instant,
    ): Duration {
        val base = entries[key]?.watched ?: Duration.ZERO
        val from = since
        return if (key == open && from != null) base + (at - from).coerceAtLeast(Duration.ZERO) else base
    }

    /** True when [key] was watched for at least the threshold of [runtime] and is not yet marked (R5, R6). */
    fun markDue(
        key: AiringKey,
        runtime: Duration?,
        at: Instant,
    ): Boolean {
        val entry = entries[key]
        val eligible = entry != null && !entry.marked && entry.markFailures < config.markAttempts
        return eligible && runtime != null && runtime > Duration.ZERO &&
            watched(key, at) >= runtime * config.playedThreshold
    }

    /** Records how marking [key] played went: done for good, or one more failure. */
    fun markResult(
        key: AiringKey,
        succeeded: Boolean,
    ) {
        val entry = entry(key)
        if (succeeded) entry.marked = true else entry.markFailures++
    }

    fun isMarked(key: AiringKey): Boolean = entries[key]?.marked == true

    private fun entry(key: AiringKey): Entry = entries.getOrPut(key) { Entry() }
}
