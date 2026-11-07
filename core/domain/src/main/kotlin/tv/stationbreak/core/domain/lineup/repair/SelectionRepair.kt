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

/**
 * What a selection repair of [seq] records: a repair per channel day, the empty fixes of its kept gone rests, and its
 * pins.
 */
internal class PlannedRepair(
    val seq: Long,
    val repairs: List<ChannelRepair>,
    val fixes: List<LostFix>,
    val pins: List<Pin>,
) {
    /**
     * [record] with these in place of what it held for the same channel days (the latest repair of a day wins), and
     * [seq] in its `lastSeq` if higher (none while no selection repair is held). The lost-block check is cleared: the
     * new tails were never checked, so the next `repairLost` checks them.
     */
    fun into(record: TodayRepairs): TodayRepairs {
        val days = repairs.mapTo(HashSet()) { it.channel to it.n }
        val selection = record.selection.filterNot { (it.channel to it.n) in days } + repairs
        return record.copy(
            selection = selection,
            fixes = record.fixes.filterNot { (it.channel to it.n) in days } + fixes,
            lostCheck = null,
            lastSeq = if (selection.isEmpty()) 0L else maxOf(record.latestSeq(), seq),
        )
    }
}

/**
 * Works out one change of the selection (spec 26) for the days on air at the change: each channel day keeps its kept
 * window ([KeptWindow]) and gets a tail laid out by the scheduler from the pin's end over its tail pool
 * ([LineupDays.tailPoolOf]). The new selection is read once per cut-off (`ruleSnapshot`, the build's own chunked
 * pool read); what each channel admits from it is the build's own ([LineupDays.admitted]) and the homes of what is
 * added are the build's own over the day's frozen curation ([LineupDays.homesFor]). On a day already repaired, the
 * current selection is read too (once per cut-off), for what the channel admits of it. The libraries the change
 * removes are read only to name a pinned programme's library. It runs before the removed rows go.
 *
 * Every switched-on channel's day gets a repair, so the first tune after the change reads the record alone. A day
 * hidden at the change has nothing on air to keep: its rest is laid out from the half hour, or, when that holds
 * nothing either, the whole day is one slate with the new selection's empty reason.
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
    private var admittedBefore: Admitted? = null
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
        if (pairs.any { (day, _) -> day.record?.repair != null }) {
            val (bp, bf) = snapshotsOf(library, account, change.before, scheduled.map { it.cut }.distinct())
            admittedBefore = days.admitted(held.lineup, scheduled, bp, bf, change.before)
        }
        val titles = HashSet<CanonicalId>()
        for ((day, _) in pairs) titles += ownTitles(day)
        val shown = pairs.filter { (day, _) -> day.hiddenAt(atMs) == null }
        fills.live(shown.flatMap { (day, _) -> KeptWindow.programmesOf(day, atMs) })
        val repairs = ArrayList<ChannelRepair>()
        val fixes = ArrayList<LostFix>()
        for ((day, s) in pairs) {
            // A day hidden at the change has nothing on the air to keep.
            val kept = if (day.hiddenAt(atMs) == null) KeptWindow.of(day, atMs, fills) else null
            repairs += repairOf(day, s, kept, titles)
            kept?.let { fixes += it.fixes }
        }
        val pins = repairs.mapNotNull { r -> r.pinLibrary?.takeIf { it in removed }?.let { Pin(it, r.pinEndMs) } }
        return PlannedRepair(change.seq, repairs, fixes, pins)
    }

    // [day]'s repair. With no [kept] window (a day hidden at the change) its tail starts at the half hour, and when
    // that holds nothing either the whole day is one slate, hidden with the new selection's reason.
    private suspend fun repairOf(
        day: ChannelDay,
        s: Scheduled,
        kept: KeptWindow?,
        today: Set<CanonicalId>,
    ): ChannelRepair {
        // The snapshot of the new selection that [s]'s day reads (with the extras out, for a filtered day).
        val snapshot = (if (s.extras) filtered else plain).getValue(s.cutoffMs)
        val fromMs = kept?.fromMs ?: maxOf(day.epochStartMs, change.atMs - Math.floorMod(change.atMs, KEPT_STEP_MS))
        val tail = tailOf(day, s, snapshot, kept?.pinEndMs ?: fromMs, kept?.aired.orEmpty(), today)
        val nothing = tail.isNotEmpty() && tail.none { it.content is ScheduleContent.Program }
        // Hidden at the change and after it: hidden all day, from its start, so no window ever shows it.
        val offAir = kept == null && nothing
        val start = if (offAir) day.epochStartMs else fromMs
        val blocks =
            if (offAir) {
                listOf(KeptBlock(day.epochStartMs, day.epochEndMs, null, 0))
            } else {
                kept?.blocks.orEmpty() + tail.map { it.kept() }
            }
        val reason = admitted.days[day.channel.id]?.hidden ?: day.hiddenAt(change.atMs) ?: HiddenReason.EmptyToday
        return ChannelRepair(
            channel = day.channel.id,
            n = day.n,
            dayEndMs = day.epochEndMs,
            atMs = change.atMs,
            seq = change.seq,
            libraries = change.after,
            keptFromMs = start,
            pinEndMs = kept?.pinEndMs ?: start,
            pinLibrary = kept?.pinItem?.let { libraryOf(it, s, snapshot) },
            blocks = blocks,
            tailHidden = if (nothing) reason else null,
        )
    }

    // The scheduler's tail of [day] from [fromMs] over its tail pool, with [aired] left out; none at the day's end.
    @Suppress("LongParameterList") // The day, what it reads, and where the tail starts.
    private suspend fun tailOf(
        day: ChannelDay,
        s: Scheduled,
        snapshot: RuleSnapshot,
        fromMs: Long,
        aired: Set<CanonicalId>,
        today: Set<CanonicalId>,
    ): List<ScheduleBlock> {
        if (fromMs >= day.epochEndMs) return emptyList()
        val repaired = day.record?.repair != null
        val pool = tailPool(day, s, snapshot, repaired, today)
        val (films, weights) = sidesOf(day, pool, repaired, addedIn(s, snapshot))
        return layoutTail(day.settings, pool, day.n, films, weights, fromMs, aired)
    }

    // The tail pool of [day]: its base (the day pool as built; on a repaired day the record's tail pool and what the
    // channel admits of the current selection, less what the record has airing before the change) without what the
    // new selection lacks, and what it takes of the added libraries; only items with a current row.
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
                // The record keeps only the tail's items: the rest of the day's pool comes from what the channel
                // admits now, so a second change can lay out what the first tail did not.
                val now = admittedBefore?.pool(day.channel.id).orEmpty()
                val union = LinkedHashMap<CanonicalId, ScheduleItem>()
                for (item in dayPool) union[item.id] = item
                for (item in now) union.putIfAbsent(item.id, item)
                union.values.filter { it.id !in aired }
            } else {
                dayPool
            }
        val present = presentBy.getOrPut(s.cutoffMs to s.extras) { snapshot.items.mapTo(HashSet()) { it.item.id } }
        val addedIds = addedIn(s, snapshot)
        val taken = admitted.pool(day.channel.id).filter { it.id in addedIds }
        val own = ownTitles(day)
        val pool = days.tailPoolOf(s, base, present, taken, homesOf(s, snapshot), today, own)
        // An item deleted since the cut-off is still in the snapshot: a fresh tail never lays out one with no row.
        val live = fills.live(pool.map { it.id })
        return pool.filter { it.id in live }
    }

    // The titles of [day]'s pool today: its day pool, and on a repaired day what the channel admits now as well.
    private suspend fun ownTitles(day: ChannelDay): Set<CanonicalId> {
        val out = fills.dayPool(day).mapTo(HashSet()) { it.groupId }
        if (day.record?.repair != null) admittedBefore?.pool(day.channel.id)?.mapTo(out) { it.groupId }
        return out
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
     * The films and weights the tail is laid out with, only for what [pool] holds. On a day with no repair: the day's
     * own (as built today) for everything it held, and the new selection's build only for the added films and new
     * titles. Today's entry wins where both have one, so a title the new selection would weigh differently (say, an
     * anchor now) keeps today's weight until the boundary. On a repaired day, whose build is not today's: the new
     * selection's build for all of the pool. `TodayRepairerGuardsTest` pins each of these.
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
