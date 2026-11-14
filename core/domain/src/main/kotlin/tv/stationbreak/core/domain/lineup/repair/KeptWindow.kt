// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.scheduler.PastAiring

/** The length of the steps the kept window starts on: it starts at the half hour at or before the repair. */
internal const val KEPT_STEP_MS = 30 * 60 * 1000L

/**
 * The kept window of one channel day at a repair (spec 26): what airs from [fromMs] (the half hour at or before the
 * repair, never before the day's start) to [pinEndMs] (the end of the block on air, the pin), exactly as the viewer
 * sees it now. A gone block in it is kept as the schedule source names it: the gap fill's pieces as programmes, and
 * the time they leave as the gone item, with an empty fix ([fixes]) so it is never filled again. [aired] is what
 * aired or airs before the pin's end, for the tail to leave out; [airings] is what the viewer saw air before the pin's
 * end, each with its end, for the spread end of the day (empty on a day that does not spread: nothing reads it);
 * [pinItem] is the programme on air (null for a slate, a break or a gone rest).
 */
@Suppress("LongParameterList") // The window's facts, one for one.
internal class KeptWindow(
    val fromMs: Long,
    val pinEndMs: Long,
    val blocks: List<KeptBlock>,
    val fixes: List<LostFix>,
    val aired: Set<CanonicalId>,
    val airings: List<KeptAiring>,
    val pinItem: CanonicalId?,
) {
    /**
     * [airings] as the tail's `pastAirings` (ADR-0025), each item resolved by ID: from [day]'s pool as built, else its
     * day pool (on a repaired day the recorded tail's items), else read by ID at the day's cut-off (on a repaired day,
     * what an earlier repair kept before its tail). An item none of them gives (it was deleted before the cut-off) is
     * left out. Every ID is in [aired].
     */
    suspend fun pastAirings(
        day: ChannelDay,
        fills: Fills,
    ): List<PastAiring> {
        if (airings.isEmpty()) return emptyList()
        val byId = HashMap<CanonicalId, ScheduleItem>()
        for (item in fills.dayPool(day)) byId[item.id] = item
        for (item in day.pool) byId[item.id] = item
        val missing = airings.mapNotNullTo(HashSet()) { it.itemId.takeIf { id -> id !in byId } }
        for (item in fills.itemsAt(day, missing)) byId.putIfAbsent(item.id, item)
        return airings.mapNotNull { airing -> byId[airing.itemId]?.let { PastAiring(it, airing.endMs) } }
    }

    companion object {
        /**
         * The ids of the programmes of [day] that [of] reads for the window at [atMs], to ask about at once: the
         * window's, and on a day that spreads its end also those that ended before the window.
         */
        fun programmesOf(
            day: ChannelDay,
            atMs: Long,
        ): List<CanonicalId> {
            val (from, window) = windowOf(day, atMs)
            val before = if (spreads(day)) day.day.blocks.filter { it.endMs <= from } else emptyList()
            return (before + window).mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }
        }

        /**
         * The window of [day] at [atMs], for the selection repair of [seq] (its fixes are of that repair); [fills] has
         * been asked about [programmesOf] already.
         */
        suspend fun of(
            day: ChannelDay,
            atMs: Long,
            seq: Long,
            fills: Fills,
        ): KeptWindow {
            val (from, window) = windowOf(day, atMs)
            val pinEnd = window.last().endMs
            val spans = ArrayList<Span>()
            for (block in window) spans += spansOf(day, block, fills)
            val clipped = spans.mapNotNull { it.from(from) }
            val fixes =
                clipped.filter { it.gone }.map {
                    val gone = checkNotNull(it.item())
                    LostFix(day.channel.id, day.n, day.epochEndMs, it.startMs, it.endMs, gone, emptyList(), seq)
                }
            val aired = HashSet<CanonicalId>()
            for (block in day.day.blocks) {
                if (block.startMs >= from) break
                (block.content as? ScheduleContent.Program)?.let { aired += it.itemId }
            }
            for (span in clipped) if (!span.gone) span.item()?.let { aired += it }
            val airings = if (spreads(day)) airingsOf(day, from, clipped, fills) else emptyList()
            val pin = clipped.first { atMs >= it.startMs && atMs < it.endMs }
            val pinItem = pin.item()?.takeUnless { pin.gone }
            return KeptWindow(
                from,
                pinEnd,
                clipped.map { KeptBlock(it.startMs, it.endMs, it.content) },
                fixes,
                aired,
                airings,
                pinItem,
            )
        }

        private fun spreads(day: ChannelDay): Boolean = day.settings.leftover == LeftoverPolicy.Spread

        // What the viewer saw air before the pin's end, each with its real end: the programmes that ended before the
        // window, less a gone one (its item has no current row, or it is a fix's rest), then the window's spans that
        // are not gone (the gap fill's pieces among them). A gone block before the window was shown as its gap fill,
        // which is not worked out again here, so it counts for nothing.
        private fun airingsOf(
            day: ChannelDay,
            from: Long,
            clipped: List<Span>,
            fills: Fills,
        ): List<KeptAiring> {
            val airings = ArrayList<KeptAiring>()
            for (block in day.day.blocks.takeWhile { it.endMs <= from }) {
                val item = (block.content as? ScheduleContent.Program)?.itemId
                val gone = item == null || !fills.isLive(item) || block.startMs in day.fixRemainders
                if (!gone) airings += KeptAiring(checkNotNull(item), block.endMs)
            }
            for (span in clipped) if (!span.gone) span.item()?.let { airings += KeptAiring(it, span.endMs) }
            return airings
        }

        // The window's start and the blocks of [day] that overlap it, up to the block on air at [atMs].
        private fun windowOf(
            day: ChannelDay,
            atMs: Long,
        ): Pair<Long, List<ScheduleBlock>> {
            val from = maxOf(day.epochStartMs, atMs - Math.floorMod(atMs, KEPT_STEP_MS))
            val blocks = day.day.blocks
            val pin = blocks.first { atMs >= it.startMs && atMs < it.endMs }
            return from to blocks.filter { it.endMs > from && it.startMs < pin.endMs }
        }

        // [block] as the source names it: a live programme, a slate or a break as it is; a gone one as its fill, then
        // the rest.
        private suspend fun spansOf(
            day: ChannelDay,
            block: ScheduleBlock,
            fills: Fills,
        ): List<Span> {
            val content =
                block.content as? ScheduleContent.Program
                    ?: return listOf(Span(block.startMs, block.endMs, block.kept().content))
            val kept = KeptContent.Program(content.itemId, content.mediaStartOffsetMs)
            return when {
                fills.isLive(content.itemId) -> listOf(Span(block.startMs, block.endMs, kept))
                block.startMs in day.fixRemainders -> listOf(Span(block.startMs, block.endMs, kept, gone = true))
                else -> filledSpans(day, block, content, fills)
            }
        }

        // A gone [block] that is not a fix's rest, as the source names it: the gap fill, then the time it leaves.
        private suspend fun filledSpans(
            day: ChannelDay,
            block: ScheduleBlock,
            content: ScheduleContent.Program,
            fills: Fills,
        ): List<Span> {
            val item = content.itemId
            val media = content.mediaStartOffsetMs
            val fixPieces =
                day.record
                    ?.fixHolding(block.startMs)
                    ?.pieces
                    ?.mapNotNullTo(HashSet()) { it.programId() }
                    .orEmpty()
            val pieces = fills.pick(day, fills.dayPool(day), block.startMs, block.endMs, item, fixPieces)
            val out = pieces.mapTo(ArrayList()) { Span(it.startMs, it.endMs, it.content) }
            val covered = pieces.lastOrNull()?.endMs ?: block.startMs
            if (covered < block.endMs) {
                val rest = KeptContent.Program(item, media + (covered - block.startMs))
                out += Span(covered, block.endMs, rest, gone = true)
            }
            return out
        }
    }

    // One stretch of the window: what it airs ([content]), and for a programme whether it is the rest of a gone item
    // ([gone]). Only a programme can be gone: a slate or a break names no item.
    private class Span(
        val startMs: Long,
        val endMs: Long,
        val content: KeptContent,
        val gone: Boolean = false,
    ) {
        init {
            require(!gone || content is KeptContent.Program) { "Only a programme can be the rest of a gone item" }
        }

        fun item(): CanonicalId? = (content as? KeptContent.Program)?.itemId

        // This span from [fromMs] on (a programme's media start moved on by what is cut), or null when it ends by then.
        fun from(fromMs: Long): Span? =
            when {
                endMs <= fromMs -> null
                startMs >= fromMs -> this
                else -> Span(fromMs, endMs, content.cut(fromMs - startMs), gone)
            }

        private fun KeptContent.cut(byMs: Long): KeptContent =
            when (this) {
                is KeptContent.Program -> copy(mediaStartMs = mediaStartMs + byMs)
                KeptContent.Slate, KeptContent.Break -> this
            }
    }
}

/** A programme that aired before a repair's tail: its [itemId] and when it ended ([endMs]) as the viewer saw it. */
internal data class KeptAiring(
    val itemId: CanonicalId,
    val endMs: Long,
)
