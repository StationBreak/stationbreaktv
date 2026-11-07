// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.repair.ChannelRepair
import tv.stationbreak.core.domain.lineup.repair.Composed
import tv.stationbreak.core.domain.lineup.repair.LostFix
import tv.stationbreak.core.domain.lineup.repair.RepairedDay
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleItem

/** How a repaired day's tail pool is read: the items of `ids` as held before `cutoffMs` (`scheduleItemsOf`). */
internal typealias TailRead = suspend (ids: Set<CanonicalId>, cutoffMs: Long) -> List<ScheduleItem>

/**
 * What the per-TV repair record (spec 26) holds for one channel day: its [repair] (null when there is none, or when
 * the stored one does not compose, which a saved record never does), its [fixes] in record order, and [digest]
 * (`TodayRepairs.digestFor`), which the day's keys carry so a change of the record is a change of exactly this day.
 * [seq] is the seq of the selection repair whose fixes these are (0: of the day as built), the one a new fix of this
 * day carries: so a fix written for the day is always one the day then shows, even when its stored repair does not
 * compose and [repair] is null.
 */
internal class DayRecord(
    val repair: ChannelRepair?,
    val fixes: List<LostFix>,
    val digest: String,
    val seq: Long,
) {
    /** The distinct items of the recorded tail (the blocks from the pin's end), in the record's order. */
    val tailIds: Set<CanonicalId> =
        repair
            ?.blocks
            ?.filter { it.startMs >= repair.pinEndMs }
            ?.mapNotNullTo(LinkedHashSet()) { it.itemId }
            .orEmpty()

    // The tail pool once read: the same record gives the same items, so it is read once per record and process.
    @Volatile private var tail: List<ScheduleItem>? = null

    /** The tail pool: [tailIds] as [read] gives them, read on first use only (and not at all for an empty tail). */
    suspend fun tailPool(read: suspend (Set<CanonicalId>) -> List<ScheduleItem>): List<ScheduleItem> {
        tail?.let { return it }
        val items = if (tailIds.isEmpty()) emptyList() else read(tailIds)
        tail = items
        return items
    }

    /**
     * The day `[startMs, endMs)` as the record composes it ([RepairedDay.compose], given only this day's fixes): a
     * repaired day from the record alone, never reading [original]; any other the build's blocks with the fixes.
     */
    fun compose(
        startMs: Long,
        endMs: Long,
        original: () -> List<ScheduleBlock>,
    ): Composed =
        if (repair != null) {
            RepairedDay.compose(startMs, endMs, emptyList(), repair, fixes)
        } else {
            RepairedDay.compose(startMs, endMs, original(), null, fixes)
        }

    /** The fix whose block holds [atMs] (the last of the record's, as [compose] takes it), or null. */
    fun fixHolding(atMs: Long): LostFix? = fixes.lastOrNull { atMs >= it.blockStartMs && atMs < it.blockEndMs }
}

/** What this record holds for [s]'s day, or null when nothing is recorded for it. */
internal fun TodayRepairs.recordFor(s: Scheduled): DayRecord? {
    val id = s.channel.id
    val digest = digestFor(id, s.n)
    if (digest.isEmpty()) return null
    val start = s.dayStartMs
    val end = start + s.settings.epochMs
    val latest = selection.filter { it.channel == id && it.n == s.n }.maxByOrNull { it.seq }
    val repair = latest?.takeIf { RepairedDay.compose(start, end, emptyList(), it, emptyList()).blocks.isNotEmpty() }
    // Only the fixes of the repair it holds (seq 0: of the day as built), as `TodayRepairs.applied` shows them.
    val seq = latest?.seq ?: 0L
    return DayRecord(repair, fixes.filter { it.channel == id && it.n == s.n && it.seq == seq }, digest, seq)
}

/** The record of every day of [scheduled] that has one, by channel; empty, and nothing worked out, with no record. */
internal fun TodayRepairs.recordsFor(scheduled: List<Scheduled>): Map<ChannelId, DayRecord> {
    if (selection.isEmpty() && fixes.isEmpty()) return emptyMap()
    val out = HashMap<ChannelId, DayRecord>()
    for (s in scheduled) recordFor(s)?.let { out[s.channel.id] = it }
    return out
}

/** The digests ([TodayRepairs.digestFor]) of the days of [scheduled] that have a record, by channel. */
internal fun TodayRepairs.digestsFor(scheduled: List<Scheduled>): Map<ChannelId, String> {
    if (selection.isEmpty() && fixes.isEmpty()) return emptyMap()
    val out = HashMap<ChannelId, String>()
    for (s in scheduled) digestFor(s.channel.id, s.n).takeIf { it.isNotEmpty() }?.let { out[s.channel.id] = it }
    return out
}
