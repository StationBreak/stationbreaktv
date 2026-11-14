// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent

/** The length of the steps the kept window starts on: it starts at the half hour at or before the repair. */
internal const val KEPT_STEP_MS = 30 * 60 * 1000L

/**
 * The kept window of one channel day at a repair (spec 26): what airs from [fromMs] (the half hour at or before the
 * repair, never before the day's start) to [pinEndMs] (the end of the block on air, the pin), exactly as the viewer
 * sees it now. A gone block in it is kept as the schedule source names it: the gap fill's pieces as programmes, and
 * the time they leave as the gone item, with an empty fix ([fixes]) so it is never filled again. [aired] is what
 * aired or airs before the pin's end, for the tail to leave out; [pinItem] is the programme on air (null for a slate,
 * a break or a gone rest).
 */
internal class KeptWindow(
    val fromMs: Long,
    val pinEndMs: Long,
    val blocks: List<KeptBlock>,
    val fixes: List<LostFix>,
    val aired: Set<CanonicalId>,
    val pinItem: CanonicalId?,
) {
    companion object {
        /** The ids of the programmes of [day] that [of] reads for the window at [atMs], to ask about at once. */
        fun programmesOf(
            day: ChannelDay,
            atMs: Long,
        ): List<CanonicalId> {
            val window = windowOf(day, atMs).second
            return window.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }
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
            val pin = clipped.first { atMs >= it.startMs && atMs < it.endMs }
            val pinItem = pin.item()?.takeUnless { pin.gone }
            return KeptWindow(
                from,
                pinEnd,
                clipped.map { KeptBlock(it.startMs, it.endMs, it.content) },
                fixes,
                aired,
                pinItem,
            )
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
