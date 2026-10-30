// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

/** A stretch of media that was played: [startMs] up to, not including, [endMs]. */
data class MediaSpan(
    val startMs: Long,
    val endMs: Long,
)

/**
 * One airing's played media spans ([MediaSpan], half-open, sorted and disjoint) and whether the app marked it played,
 * carried across a screen stop with a kept place (spec 20). Public plain data; held in memory only.
 */
data class WatchSnapshot(
    val key: AiringKey,
    val spans: List<MediaSpan>,
    val marked: Boolean,
)

/**
 * The open airing as the ledger last had it: its closed [spans] and mark, and the interval running from [fromMs] since
 * [since] (null while stopped). [at] closes that interval at a later time without the ledger.
 */
internal class OpenAiring(
    val key: AiringKey,
    val spans: List<MediaSpan>,
    val marked: Boolean,
    val fromMs: Long,
    val since: Instant?,
) {
    fun at(now: Instant): WatchSnapshot {
        val from = since ?: return WatchSnapshot(key, spans, marked)
        val end = fromMs + (now - from).coerceAtLeast(Duration.ZERO).inWholeMilliseconds
        return WatchSnapshot(key, WatchLedger.merge(spans, MediaSpan(fromMs, end)), marked)
    }
}

/**
 * What the viewer actually watched, per airing, for the life of one `LiveChannel.run` (spec 16, R3/R4/R5/R7; spec 20).
 * Pure: every method is told the time, so virtual time drives it. Not thread-safe; the reporter uses it from one
 * coroutine.
 *
 * Watched time is the length of the union of the media spans played, so a rewatch counts once and a skip counts
 * nothing; it adds up across flips away and back. The open airing runs only while it is not stalled, not paused and
 * not seeking; its media position then advances at 1x from [fromMs].
 */
@Suppress("TooManyFunctions") // One pure ledger: each transport state is one method on the same open interval.
internal class WatchLedger(
    private val config: ReportingConfig,
) {
    private class Entry {
        /** Sorted, disjoint, half-open, and not touching. */
        var spans: List<MediaSpan> = emptyList()
        var marked = false
        var markFailures = 0
    }

    private val entries = mutableMapOf<AiringKey, Entry>()

    /** How many airings are remembered. */
    val size: Int get() = entries.size

    private var sittingId: Long? = null
    private var sittingStart: Instant? = null
    private var open: AiringKey? = null

    /** The open airing's media position when it last stopped or was positioned. */
    private var fromMs: Long = 0

    /** When the open airing began running from [fromMs]; null while it does not run or none is open. */
    private var since: Instant? = null
    private var stalled = false
    private var paused = false
    private var seeking = false

    fun sittingStarted(
        id: Long,
        at: Instant,
    ) {
        sittingId = id
        sittingStart = at
    }

    /**
     * How long to wait before reporting an item that just began in sitting [id] (R4): what is left of `startAfter`
     * since the sitting began, so none once the sitting is that old; the full `startAfter` for any other sitting.
     */
    fun dwell(
        id: Long,
        at: Instant,
    ): Duration {
        val start = sittingStart
        val age = if (sittingId == id && start != null) (at - start).coerceAtLeast(Duration.ZERO) else Duration.ZERO
        return (config.startAfter - age).coerceAtLeast(Duration.ZERO)
    }

    /**
     * Starts counting [key] from media position [fromMs]; any airing still open is closed first. Earlier blocks of
     * the same channel are over, so their entries are dropped; the map is also capped, oldest first, so a long run
     * cannot grow it without bound.
     */
    fun enter(
        key: AiringKey,
        at: Instant,
        fromMs: Long,
    ) {
        leave(at)
        entries.keys.removeAll { it.channelId == key.channelId && it.blockStartMs < key.blockStartMs }
        entry(key)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first { it != key })
        open = key
        this.fromMs = fromMs
        stalled = false
        paused = false
        seeking = false
        since = at
    }

    /** Media stops (a stall or a failed play) at [positionMs] when known, else at the derived position. */
    fun stall(
        at: Instant,
        positionMs: Long? = null,
    ) {
        if (open == null) return
        stop(at, positionMs)
        stalled = true
    }

    /** Media runs again from where it stopped, or from [fromMs] (a retry at the place). */
    fun resumed(
        at: Instant,
        fromMs: Long? = null,
    ) {
        if (open == null || !stalled) return
        stalled = false
        if (fromMs != null) this.fromMs = fromMs
        runIfClear(at)
    }

    fun pause(
        at: Instant,
        positionMs: Long? = null,
    ) {
        if (open == null) return
        stop(at, positionMs)
        paused = true
    }

    fun unpause(at: Instant) {
        if (open == null || !paused) return
        paused = false
        runIfClear(at)
    }

    /**
     * A seek began from [positionMs], the player's exact position just before it when known (else the derived one):
     * counting stops there until [seeked], so both ends of the seek are in the player's coordinates.
     */
    fun seeking(
        at: Instant,
        positionMs: Long? = null,
    ) {
        if (open == null) return
        stop(at, positionMs)
        seeking = true
    }

    /** The seek is done at [toMs] (the player's `SeekDone.positionMs`): counting may run again from there. */
    fun seeked(
        at: Instant,
        toMs: Long,
    ) {
        if (open == null || !seeking) return
        seeking = false
        fromMs = toMs
        runIfClear(at)
    }

    /** Closes the open airing; time while it was stopped is simply not counted. */
    fun leave(at: Instant) {
        if (open == null) return
        stop(at, null)
        open = null
        since = null
        stalled = false
        paused = false
        seeking = false
    }

    /** The total length of the union of what was played, the open interval included. */
    fun watched(
        key: AiringKey,
        at: Instant,
    ): Duration =
        snapshot(key, at)
            ?.spans
            .orEmpty()
            .sumOf { it.endMs - it.startMs }
            .milliseconds

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

    /** [key]'s played spans and mark, the open interval closed at [at]; null when [key] is not remembered. */
    fun snapshot(
        key: AiringKey,
        at: Instant,
    ): WatchSnapshot? {
        val entry = entries[key] ?: return null
        return OpenAiring(key, entry.spans, entry.marked, fromMs, since.takeIf { key == open }).at(at)
    }

    /** Unites [snapshot] (watched time from before a screen stop) into the entry for its key, and keeps its mark. */
    fun seed(snapshot: WatchSnapshot) {
        val entry = entry(snapshot.key)
        entry.spans = snapshot.spans.fold(entry.spans, ::merge)
        if (snapshot.marked) entry.marked = true
    }

    /** The open airing as it stands, for the reporter to publish off its worker; null when none is open. */
    fun openAiring(): OpenAiring? {
        val key = open ?: return null
        return entries[key]?.let { OpenAiring(key, it.spans, it.marked, fromMs, since) }
    }

    fun isMarked(key: AiringKey): Boolean = entries[key]?.marked == true

    private fun entry(key: AiringKey): Entry = entries.getOrPut(key) { Entry() }

    /** While the open airing runs, closes its span at [positionMs] (never past the derived position) and stops it. */
    private fun stop(
        at: Instant,
        positionMs: Long?,
    ) {
        val key = open ?: return
        val from = since ?: return
        val derived = fromMs + (at - from).coerceAtLeast(Duration.ZERO).inWholeMilliseconds
        // An exact position may only shorten the span: the player can land ahead of where it was asked, and keeps
        // playing before the span's clock is stamped, so it could read ahead of the wall time. Never over-count.
        val end = if (positionMs == null) derived else minOf(positionMs, derived)
        val entry = entry(key)
        entry.spans = merge(entry.spans, MediaSpan(fromMs, end))
        fromMs = end
        since = null
    }

    private fun runIfClear(at: Instant) {
        if (!stalled && !paused && !seeking) since = at
    }

    companion object {
        /** The most airings remembered: far more than a viewer can flip through, small enough to stay tiny. */
        const val MAX_ENTRIES = 256

        /** [spans] plus [add] (clipped at 0), joined where they overlap or touch. */
        internal fun merge(
            spans: List<MediaSpan>,
            add: MediaSpan,
        ): List<MediaSpan> {
            val start = maxOf(add.startMs, 0L)
            if (add.endMs <= start) return spans
            var from = start
            var to = add.endMs
            val result = mutableListOf<MediaSpan>()
            for (span in spans) {
                when {
                    span.endMs < from -> {
                        result += span
                    }

                    span.startMs > to -> {
                        result += span
                    }

                    else -> {
                        from = minOf(from, span.startMs)
                        to = maxOf(to, span.endMs)
                    }
                }
            }
            result += MediaSpan(from, to)
            return result.sortedBy { it.startMs }
        }
    }
}
