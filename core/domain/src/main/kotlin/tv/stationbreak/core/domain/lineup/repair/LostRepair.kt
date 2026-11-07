// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.Admitted
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.domain.lineup.snapshotsOf
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * Works out the fixes of the blocks whose item has left the library (spec 26), from the block on air at [atMs] to
 * each day's end. One `currentIds` read over the remaining blocks' items finds them (a pool's items are asked about
 * only if not already). A gone block is matched to its item's successor ([successors]) when the channel admits it
 * today ([Admitted.admits]: its rules for the day, the kid and grown-up holds, airable) and it is not gone itself, the
 * match airing in the block's slot from the block's own media start; else it is filled ([GapFill]) from the repair
 * fill pool: the day pool on a day with no selection repair, and on a repaired day its tail pool plus what the
 * channel admits of the [playable] libraries now, read once per cut-off with the build's own chunked pool read. With
 * no [playable] libraries (the selection is gone) nothing is read from the libraries: the fill is the tail pool's, or
 * nothing (the card).
 *
 * What is on air at [atMs] does not change: its block is filled as the schedule source fills it now (the day pool, no
 * match), and a gone piece of a recorded fix on air is left to the source. A gone piece of a recorded fix further on is
 * filled again from its start to the fix's end, around the fix's other pieces, and the fix replaced. A recorded fix's
 * unnamed rest is never filled again.
 */
@Suppress("LongParameterList") // The reads it makes, then the one check: whose, from which libraries, and when.
internal class LostRepair(
    private val days: LineupDays,
    private val library: LibraryRepository,
    private val successors: Successors,
    private val account: LibraryAccount,
    private val held: Revisioned,
    private val playable: Set<ItemId>?,
    private val atMs: Long,
) {
    private val fills = Fills(library, account)
    private var admitted: Admitted? = null

    // A gone block of [day]: the fix whose block holds it, when it is a piece of one; whether it is on air.
    private class Gone(
        val day: ChannelDay,
        val block: ScheduleBlock,
        val item: CanonicalId,
        val holding: LostFix?,
        val onAir: Boolean,
    )

    /** The fixes of [today]'s gone blocks (the days on air at [atMs]), at most one per block fixed. */
    suspend fun fixes(today: List<ChannelDay>): List<LostFix> {
        val ahead = today.map { day -> day to day.day.blocks.filter { it.endMs > atMs } }
        fills.live(ahead.flatMap { (_, blocks) -> blocks.mapNotNull { it.programId() } })
        val gone = ahead.flatMap { (day, blocks) -> blocks.mapNotNull { goneOf(day, it) } }
        if (gone.isEmpty()) return emptyList()
        val goneIds = gone.mapTo(HashSet()) { it.item }
        val matches = matchesOf(gone)
        fills.live(matches.values)
        val needsAdmitted = matches.isNotEmpty() || gone.any { !it.onAir && it.day.record?.repair != null }
        if (playable != null && needsAdmitted) admitted = admittedOf(playable)
        val out = LinkedHashMap<FixKey, LostFix>()
        for (g in gone) {
            val fix = fixOf(g, matches[g.item]?.takeIf { accepts(g, it, goneIds) })
            out.putIfAbsent(FixKey.of(fix), fix)
        }
        return out.values.toList()
    }

    private fun ScheduleBlock.programId(): CanonicalId? = (content as? ScheduleContent.Program)?.itemId

    private fun goneOf(
        day: ChannelDay,
        block: ScheduleBlock,
    ): Gone? {
        val item = block.programId()
        if (item == null || fills.isLive(item) || block.startMs in day.fixRemainders) return null
        val holding = day.record?.fixHolding(block.startMs)
        val onAir = atMs >= block.startMs && atMs < block.endMs
        return if (holding != null && onAir) null else Gone(day, block, item, holding, onAir)
    }

    // The successors of the gone scheduled items not on air, among the playable libraries; none when they are gone.
    private suspend fun matchesOf(gone: List<Gone>): Map<CanonicalId, CanonicalId> {
        val asked = gone.filter { it.holding == null && !it.onAir }.mapTo(HashSet()) { it.item }
        val libraries = playable
        return if (libraries == null || asked.isEmpty()) emptyMap() else successors.of(account, libraries, asked)
    }

    private suspend fun admittedOf(libraries: Set<ItemId>): Admitted {
        val scheduled = days.scheduled(held.lineup, atMs, held.meta.extrasFromMs)
        val (plain, filtered) = snapshotsOf(library, account, libraries, scheduled.map { it.cut }.distinct())
        return days.admitted(held.lineup, scheduled, plain, filtered, libraries)
    }

    // A match is taken only when the channel admits it today, it has a current row and it is not gone itself.
    private fun accepts(
        g: Gone,
        candidate: CanonicalId,
        goneIds: Set<CanonicalId>,
    ): Boolean {
        val admits = admitted?.admits(g.day.channel.id, candidate) == true
        return admits && candidate !in goneIds && fills.isLive(candidate)
    }

    private suspend fun fixOf(
        g: Gone,
        match: CanonicalId?,
    ): LostFix {
        val day = g.day
        val block = g.block
        val holding = g.holding
        return when {
            holding != null -> {
                val before = holding.pieces.filter { it.endMs <= block.startMs }
                val around = holding.pieces.mapNotNullTo(HashSet()) { it.itemId }
                val picked = fills.pick(day, poolOf(g), block.startMs, holding.blockEndMs, g.item, around)
                holding.copy(pieces = before + picked)
            }

            match != null -> {
                val media = (block.content as ScheduleContent.Program).mediaStartOffsetMs
                fixOf(day, block, g.item, listOf(KeptBlock(block.startMs, block.endMs, match, media)))
            }

            else -> {
                fixOf(day, block, g.item, fills.pick(day, poolOf(g), block.startMs, block.endMs, g.item, emptySet()))
            }
        }
    }

    private fun fixOf(
        day: ChannelDay,
        block: ScheduleBlock,
        gone: CanonicalId,
        pieces: List<KeptBlock>,
    ) = LostFix(day.channel.id, day.n, day.epochEndMs, block.startMs, block.endMs, gone, pieces)

    // The repair fill pool of [g]'s block: on a repaired day, the tail pool plus what the channel admits now, but for
    // the block on air, which is filled from the day pool alone, as the source fills it.
    private suspend fun poolOf(g: Gone): List<ScheduleItem> {
        val dayPool = fills.dayPool(g.day)
        val now = admitted
        if (g.onAir || g.day.record?.repair == null || now == null) return dayPool
        val out = LinkedHashMap<CanonicalId, ScheduleItem>()
        for (item in dayPool) out[item.id] = item
        for (item in now.pool(g.day.channel.id)) out.putIfAbsent(item.id, item)
        return out.values.toList()
    }
}

/**
 * This record with [fixes] in place of the fixes of the same blocks, and [generation] as the generation the lost-block
 * check last ran for.
 */
internal fun TodayRepairs.withFixes(
    fixes: List<LostFix>,
    generation: Long,
): TodayRepairs {
    val replaced = fixes.mapTo(HashSet()) { FixKey.of(it) }
    return copy(fixes = this.fixes.filterNot { FixKey.of(it) in replaced } + fixes, lostCheckedGeneration = generation)
}

/** The block a fix is of: one fix per key in the record. */
internal data class FixKey(
    val channel: ChannelId,
    val n: Long,
    val startMs: Long,
    val endMs: Long,
) {
    companion object {
        fun of(fix: LostFix): FixKey = FixKey(fix.channel, fix.n, fix.blockStartMs, fix.blockEndMs)
    }
}
