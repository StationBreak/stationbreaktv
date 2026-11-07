// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.Admitted
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.Cut
import tv.stationbreak.core.domain.lineup.DayCuration
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.domain.lineup.Scheduled
import tv.stationbreak.core.domain.lineup.snapshotsOf
import tv.stationbreak.core.engine.lineup.curation.Homes
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.scheduler.layoutTail
import kotlin.time.Instant

/** A change of the selection: [before] to [after], the change's [seq], at [atMs]. */
internal class SelectionChange(
    val before: Set<ItemId>,
    val after: Set<ItemId>,
    val seq: Long,
    val atMs: Long,
)

/** What a selection repair records: a repair per channel day, the empty fixes of its kept gone rests, and its pins. */
internal class PlannedRepair(
    val repairs: List<ChannelRepair>,
    val fixes: List<LostFix>,
    val pins: List<Pin>,
) {
    /**
     * [record] with these in place of what it held for the same channel days (the latest repair of a day wins). The
     * lost-block generation is cleared: the new tails were never checked, so the next `repairLost` checks them.
     */
    fun into(record: TodayRepairs): TodayRepairs {
        val days = repairs.mapTo(HashSet()) { it.channel to it.n }
        return record.copy(
            selection = record.selection.filterNot { (it.channel to it.n) in days } + repairs,
            fixes = record.fixes.filterNot { (it.channel to it.n) in days } + fixes,
            lostCheckedGeneration = null,
        )
    }
}

/**
 * Works out one change of the selection (spec 26) for the days on air at the change: each channel day keeps its kept
 * window ([KeptWindow]) and gets a tail laid out by the scheduler from the pin's end over its tail pool
 * ([LineupDays.tailPoolOf]). The new selection is read once per cut-off (`ruleSnapshot`, the build's own chunked
 * pool read); what each channel admits from it is the build's own ([LineupDays.admitted]) and the homes of what is
 * added are the build's own over the day's frozen curation ([LineupDays.homesFor]). The libraries the change removes
 * are read only to name a pinned programme's library. It runs before the removed rows go.
 */
internal class SelectionRepair(
    private val days: LineupDays,
    private val library: LibraryRepository,
    private val account: LibraryAccount,
    private val held: Revisioned,
    private val change: SelectionChange,
) {
    private val fills = Fills(library, account)
    private val removed = change.before - change.after
    private val added = (change.after - change.before).mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.value) }
    private lateinit var plain: Map<Long, RuleSnapshot>
    private lateinit var filtered: Map<Long, RuleSnapshot>
    private lateinit var admitted: Admitted
    private val homes = HashMap<Pair<Cut, DayCuration>, Homes?>()
    private val removedSnapshots = HashMap<Long, RuleSnapshot>()

    // Per snapshot of the new selection (by cut-off and extras): every item id it holds, and those it adds.
    private val presentBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()
    private val addedBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()

    /** The repairs of [today] (the days on air at the change, under the selection before it). */
    suspend fun plan(today: List<ChannelDay>): PlannedRepair {
        val atMs = change.atMs
        val scheduled = days.scheduled(held.lineup, atMs, held.meta.extrasFromMs)
        val byId = scheduled.associateBy { it.channel.id }
        val pairs = today.mapNotNull { day -> byId[day.channel.id]?.let { day to it } }
        val (p, f) = snapshotsOf(library, account, change.after, scheduled.map { it.cut }.distinct())
        plain = p
        filtered = f
        admitted = days.admitted(held.lineup, scheduled, plain, filtered, change.after)
        val titles = HashSet<CanonicalId>()
        for ((day, _) in pairs) fills.dayPool(day).mapTo(titles) { it.groupId }
        fills.live(pairs.flatMap { (day, _) -> KeptWindow.programmesOf(day, atMs) })
        val repairs = ArrayList<ChannelRepair>()
        val fixes = ArrayList<LostFix>()
        for ((day, s) in pairs) {
            val kept = KeptWindow.of(day, atMs, fills)
            val repair = repairOf(day, s, kept, titles) ?: continue
            repairs += repair
            fixes += kept.fixes
        }
        val pins = repairs.mapNotNull { r -> r.pinLibrary?.takeIf { it in removed }?.let { Pin(it, r.pinEndMs) } }
        return PlannedRepair(repairs, fixes, pins)
    }

    // The snapshot of the new selection that [s]'s day reads (with the extras out, for a filtered day).
    private fun snapshotOf(s: Scheduled): RuleSnapshot {
        val read = if (s.extras) filtered else plain
        return read.getValue(s.cutoffMs)
    }

    // [day]'s repair; null for a day hidden at the change that stays hidden (nothing of it is on the air).
    private suspend fun repairOf(
        day: ChannelDay,
        s: Scheduled,
        kept: KeptWindow,
        today: Set<CanonicalId>,
    ): ChannelRepair? {
        val snapshot = snapshotOf(s)
        val repaired = day.record?.repair != null
        val pool = tailPool(day, s, snapshot, repaired, today)
        val sides = sidesOf(day, pool, repaired, addedIn(s, snapshot))
        val tail =
            if (kept.pinEndMs < day.epochEndMs) {
                layoutTail(day.settings, pool, day.n, sides.first, sides.second, kept.pinEndMs, kept.aired)
            } else {
                emptyList()
            }
        val nothing = tail.isNotEmpty() && tail.none { it.content is ScheduleContent.Program }
        val tailHidden = if (nothing) admitted.days[day.channel.id]?.hidden ?: HiddenReason.EmptyToday else null
        if (day.hiddenAt(change.atMs) != null && (tail.isEmpty() || tailHidden != null)) return null
        return ChannelRepair(
            channel = day.channel.id,
            n = day.n,
            dayEndMs = day.epochEndMs,
            atMs = change.atMs,
            seq = change.seq,
            libraries = change.after,
            keptFromMs = kept.fromMs,
            pinEndMs = kept.pinEndMs,
            pinLibrary = kept.pinItem?.let { libraryOf(it, s, snapshot) },
            blocks = kept.blocks + tail.map { it.kept() },
            tailHidden = tailHidden,
        )
    }

    // The tail pool of [day]: its base (the day pool as built, or on a repaired day the record's tail not yet aired)
    // without what the new selection lacks, and what it takes of the added libraries; only items with a current row.
    private suspend fun tailPool(
        day: ChannelDay,
        s: Scheduled,
        snapshot: RuleSnapshot,
        repaired: Boolean,
        today: Set<CanonicalId>,
    ): List<ScheduleItem> {
        val dayPool = fills.dayPool(day)
        val base =
            if (repaired) {
                val aired = HashSet<CanonicalId>()
                for (block in checkNotNull(day.record?.repair).blocks) {
                    if (block.startMs < change.atMs) block.itemId?.let { aired += it }
                }
                dayPool.filter { it.id !in aired }
            } else {
                dayPool
            }
        val present = presentBy.getOrPut(s.cutoffMs to s.extras) { snapshot.items.mapTo(HashSet()) { it.item.id } }
        val addedIds = addedIn(s, snapshot)
        val taken = admitted.pool(day.channel.id).filter { it.id in addedIds }
        val own = dayPool.mapTo(HashSet()) { it.groupId }
        val pool = days.tailPoolOf(s, base, present, taken, homesOf(s, snapshot), today, own)
        // An item deleted since the cut-off is still in the snapshot: a fresh tail never lays out one with no row.
        val live = fills.live(pool.map { it.id })
        return pool.filter { it.id in live }
    }

    // The items of [snapshot] (the one [s] reads) in the libraries the change adds.
    private fun addedIn(
        s: Scheduled,
        snapshot: RuleSnapshot,
    ): Set<CanonicalId> =
        addedBy.getOrPut(s.cutoffMs to s.extras) {
            snapshot.items.filter { it.libraryId in added }.mapTo(HashSet()) { it.item.id }
        }

    // The homes of [s]'s day over the new selection, once per cut and curation; none with nothing added.
    private fun homesOf(
        s: Scheduled,
        snapshot: RuleSnapshot,
    ): Homes? {
        val curation = s.curation
        return if (curation == null || added.isEmpty()) {
            null
        } else {
            homes.getOrPut(s.cut to curation) { days.homesFor(held.lineup, s, snapshot) }
        }
    }

    /**
     * The films and weights the tail is laid out with: the day's own (as built today) for what it held, and the new
     * selection's build for the added titles; on a repaired day, whose build is not today's, the new selection's for
     * all. Only for what [pool] holds.
     */
    private fun sidesOf(
        day: ChannelDay,
        pool: List<ScheduleItem>,
        repaired: Boolean,
        addedIds: Set<CanonicalId>,
    ): Pair<FilmCollections, TitleWeights> {
        val ids = pool.mapTo(HashSet()) { it.id }
        val titles = pool.mapTo(HashSet()) { it.groupId }
        val newTitles = pool.filter { it.id in addedIds }.mapTo(HashSet()) { it.groupId }
        val after = admitted.days[day.channel.id]
        val films =
            merged(if (repaired) emptyList() else day.films.entries, after?.films?.entries.orEmpty()) {
                it in ids && (repaired || it in addedIds)
            }
        val weights =
            merged(if (repaired) emptyList() else day.weights.entries, after?.weights?.entries.orEmpty()) {
                it in titles && (repaired || it in newTitles)
            }
        val kept = films.filterKeys { it in ids }
        return FilmCollections.of(kept) to TitleWeights.of(weights.filterKeys { it in titles })
    }

    // [own] then the entries of [after] that [takes] wants and [own] lacks.
    private fun <V> merged(
        own: List<Pair<CanonicalId, V>>,
        after: List<Pair<CanonicalId, V>>,
        takes: (CanonicalId) -> Boolean,
    ): Map<CanonicalId, V> {
        val out = HashMap<CanonicalId, V>()
        for ((key, value) in own) out[key] = value
        for ((key, value) in after) if (takes(key)) out.putIfAbsent(key, value)
        return out
    }

    // The library of the pinned programme [item]: the new selection's, or else a removed library's (read for it).
    private suspend fun libraryOf(
        item: CanonicalId,
        s: Scheduled,
        snapshot: RuleSnapshot,
    ): ItemId? {
        val found =
            snapshot.items.firstOrNull { it.item.id == item }?.libraryId
                ?: removedAt(s.cutoffMs)?.items?.firstOrNull { it.item.id == item }?.libraryId
                ?: return null
        return (change.before + change.after).firstOrNull { CanonicalId.parseOrNull(it.value) == found }
            ?: ItemId(found.value)
    }

    // The removed libraries as held before [cutoffMs], read once per cut-off; null when the change removes none.
    private suspend fun removedAt(cutoffMs: Long): RuleSnapshot? {
        if (removed.isEmpty()) return null
        return removedSnapshots[cutoffMs]
            ?: library.ruleSnapshot(account, removed, Instant.fromEpochMilliseconds(cutoffMs)).also {
                removedSnapshots[cutoffMs] = it
            }
    }
}

/** [this] block as the record keeps it. */
internal fun ScheduleBlock.kept(): KeptBlock =
    when (val c = content) {
        is ScheduleContent.Program -> KeptBlock(startMs, endMs, c.itemId, c.mediaStartOffsetMs)
        ScheduleContent.Slate -> KeptBlock(startMs, endMs, null, 0)
    }
