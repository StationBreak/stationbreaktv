// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.WalkClock
import tv.stationbreak.core.domain.lineup.Admitted
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.Cut
import tv.stationbreak.core.domain.lineup.DayCuration
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.domain.lineup.Scheduled
import tv.stationbreak.core.domain.lineup.snapshotsOf
import tv.stationbreak.core.domain.lineup.splitByExtras
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

/** How a repair reads a selection's plain and filtered snapshots at some cuts, through the source's shared reader. */
internal typealias SharedRead =
    suspend (libraries: Set<ItemId>, cuts: List<Cut>) -> Pair<Map<Long, RuleSnapshot>, Map<Long, RuleSnapshot>>

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
     * [record] with these beside what it shows at [appliedSeq] and [resetSeq] (the saved selection's): per channel day
     * the repair [TodayRepairs.applied] shows, with that repair's fixes (or the fixes of the day as built), stays until
     * this change's seq is saved, so a failed save or a later `dropSelection` goes back to it whole. Every other repair
     * goes with its fixes (one a saved later seq superseded, one of a change never saved, or one below the reset
     * seq). This change's fixes (those of its kept windows, of [seq]) are added, and [seq] is the `lastSeq` if higher.
     * The lost-block check is cleared: the new tails were never checked, so the next `repairLost` checks them.
     */
    fun into(
        record: TodayRepairs,
        appliedSeq: Long,
        resetSeq: Long,
    ): TodayRepairs {
        val shown = record.applied(appliedSeq, resetSeq)
        val selection = shown.selection + repairs
        return record.copy(
            selection = selection,
            fixes = shown.fixes + fixes,
            lostCheck = null,
            lastSeq = if (selection.isEmpty()) 0L else maxOf(record.latestSeq(), seq),
        )
    }
}

/**
 * Works out one change of the selection (spec 26) for the days on air at the change: each channel day keeps its kept
 * window ([KeptWindow]) and gets a tail laid out by the scheduler from the pin's end over its tail pool
 * ([LineupDays.tailPoolOf]). The new selection is read once per cut-off (`ruleSnapshots`, the build's own chunked
 * pool read); what each channel admits from it is the build's own ([LineupDays.admitted]) and the homes of what is
 * added are the build's own over the day's frozen curation ([LineupDays.homesFor]). On a day already repaired, the
 * current selection is read too, for what the channel admits of it. The libraries the change removes are read only to
 * name a pinned programme's library, and only when some day has a programme on air at the change. All of these come
 * from one walk of the library per cut-off (#684), so they are of one state of it. It runs before the removed rows go.
 *
 * Every switched-on channel's day gets a repair, so the first tune after the change reads the record alone. A day
 * hidden at the change has nothing on air to keep: its rest is laid out from the half hour, or, when that holds
 * nothing either, the whole day is one slate with the new selection's empty reason.
 */
@Suppress(
    "LongParameterList", // What it reads, and the change.
    "TooManyFunctions", // One small step of the plan each, kept together.
)
internal class SelectionRepair(
    private val days: LineupDays,
    private val library: LibraryRepository,
    private val account: LibraryAccount,
    private val held: Revisioned,
    private val change: SelectionChange,
    // The rig and the repairer still pass the source's shared reader. The walk reads the current selection itself.
    @Suppress("unused")
    private val shared: SharedRead,
    // Times the steps (the timing line's), when given; it changes nothing.
    private val clock: RepairClock? = null,
) {
    private val fills = Fills(library, account)
    private val removed = change.before - change.after
    private val added = (change.after - change.before).mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.value) }
    private lateinit var walked: Walked
    private lateinit var admitted: Admitted
    private var admittedBefore: Admitted? = null
    private val homes = HashMap<Pair<Cut, DayCuration>, Homes?>()
    private val removedSnapshots = HashMap<Long, RuleSnapshot>()

    // A selection's snapshots as a day reads them: the plain ones, and for the cuts the extras filter applies to, the
    // filtered ones (each by cut-off).
    private class Read(
        val plain: Map<Long, RuleSnapshot>,
        val filtered: Map<Long, RuleSnapshot>,
    )

    // What one walk of the library gave: the new selection (always), the current selection (when a day is repaired),
    // and the removed libraries' plain snapshots by cut-off (when a pinned programme may need them).
    private class Walked(
        val after: Read,
        val before: Read?,
        val removed: Map<Long, RuleSnapshot>,
    )

    // Per snapshot of the new selection (by cut-off and extras): every item id it holds, and those it adds.
    private val presentBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()
    private val addedBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()

    /** The repairs of [today] (the days on air at the change, under the selection before it). */
    suspend fun plan(today: List<ChannelDay>): PlannedRepair {
        val atMs = change.atMs
        val scheduled = days.scheduled(held.lineup, atMs, held.meta.extrasFromMs)
        val byId = scheduled.associateBy { it.channel.id }
        val pairs = today.mapNotNull { day -> byId[day.channel.id]?.let { day to it } }
        val shown = pairs.filter { (day, _) -> day.hiddenAt(atMs) == null }
        val repaired = pairs.any { (day, _) -> day.record?.repair != null }
        // A removed library is read only to name the library of a pinned programme, which only a day with a programme
        // on air at the change has.
        val withRemoved = removed.isNotEmpty() && shown.any { (day, _) -> programmeOnAir(day) }
        walked = clock.time(RepairPhase.Snaps) { walk(scheduled.map { it.cut }.distinct(), repaired, withRemoved) }
        removedSnapshots.putAll(walked.removed)
        val after = walked.after
        admitted =
            clock.time(RepairPhase.Admitted) {
                days.admitted(held.lineup, scheduled, after.plain, after.filtered, change.after)
            }
        if (repaired) {
            admittedBefore = clock.time(RepairPhase.Before) { admittedBefore(scheduled) }
        }
        val titles = HashSet<CanonicalId>()
        for ((day, _) in pairs) titles += ownTitles(day)
        clock.time(RepairPhase.Kept) {
            val programmes = shown.flatMap { (day, _) -> KeptWindow.programmesOf(day, atMs) }
            clock.time(RepairPhase.Live) { fills.live(programmes) }
        }
        // Each day's kept window and tail pool first, then one read of which tail pool items have a current row, for
        // every channel at once (the read answers the same whenever it is asked), then the tails.
        val prepared = ArrayList<Prepared>(pairs.size)
        for ((day, s) in pairs) {
            // A day hidden at the change has nothing on the air to keep.
            val kept =
                clock.time(RepairPhase.Kept) {
                    if (day.hiddenAt(atMs) == null) KeptWindow.of(day, atMs, change.seq, fills) else null
                }
            prepared += prepare(day, s, kept, titles)
        }
        val ids = HashSet<CanonicalId>()
        for (p in prepared) p.pool?.mapTo(ids) { it.id }
        val live = clock.time(RepairPhase.Live) { fills.live(ids) }
        val repairs = ArrayList<ChannelRepair>()
        val fixes = ArrayList<LostFix>()
        for (p in prepared) {
            repairs += repairOf(p, live)
            p.kept?.let { fixes += it.fixes }
        }
        val pins = repairs.mapNotNull { r -> r.pinLibrary?.takeIf { it in removed }?.let { Pin(it, r.pinEndMs) } }
        return PlannedRepair(change.seq, repairs, fixes, pins)
    }

    // A day's kept window, where its repair starts and its tail, and its tail pool before the current-row check (none
    // when the tail starts at the day's end).
    private class Prepared(
        val day: ChannelDay,
        val s: Scheduled,
        val snapshot: RuleSnapshot,
        val kept: KeptWindow?,
        val fromMs: Long,
        val tailFromMs: Long,
        val pool: List<ScheduleItem>?,
    )

    // [day] made ready for its repair: with no [kept] window (a day hidden at the change) its tail starts at the half
    // hour.
    private suspend fun prepare(
        day: ChannelDay,
        s: Scheduled,
        kept: KeptWindow?,
        today: Set<CanonicalId>,
    ): Prepared {
        // The snapshot of the new selection that [s]'s day reads (with the extras out, for a filtered day).
        val snapshot = walked.after.let { if (s.extras) it.filtered else it.plain }.getValue(s.cutoffMs)
        val fromMs = kept?.fromMs ?: maxOf(day.epochStartMs, change.atMs - Math.floorMod(change.atMs, KEPT_STEP_MS))
        val tailFromMs = kept?.pinEndMs ?: fromMs
        val pool =
            if (tailFromMs >= day.epochEndMs) {
                null
            } else {
                clock.time(RepairPhase.Pools) { tailPool(day, s, snapshot, day.record?.repair != null, today) }
            }
        return Prepared(day, s, snapshot, kept, fromMs, tailFromMs, pool)
    }

    // [p]'s repair, its tail pool kept to the items in [live]. When the tail holds nothing either, a day hidden at the
    // change is one slate all day, hidden with the new selection's reason.
    private suspend fun repairOf(
        p: Prepared,
        live: Set<CanonicalId>,
    ): ChannelRepair {
        val day = p.day
        val s = p.s
        val snapshot = p.snapshot
        val kept = p.kept
        val fromMs = p.fromMs
        val tail = tailOf(day, s, snapshot, p.pool?.filter { it.id in live }, p.tailFromMs, kept?.aired.orEmpty())
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
            pinLibrary = clock.time(RepairPhase.Pin) { kept?.pinItem?.let { libraryOf(it, s, snapshot) } },
            blocks = blocks,
            tailHidden = if (nothing) reason else null,
        )
    }

    // The scheduler's tail of [day] from [fromMs] over its tail [pool], with [aired] left out; none at the day's end
    // (where there is no pool).
    @Suppress("LongParameterList") // The day, what it reads, and where the tail starts.
    private fun tailOf(
        day: ChannelDay,
        s: Scheduled,
        snapshot: RuleSnapshot,
        pool: List<ScheduleItem>?,
        fromMs: Long,
        aired: Set<CanonicalId>,
    ): List<ScheduleBlock> {
        if (pool == null) return emptyList()
        val repaired = day.record?.repair != null
        return clock.time(RepairPhase.Layout) {
            val (films, weights) = sidesOf(day, pool, repaired, addedIn(s, snapshot))
            layoutTail(day.settings, pool, day.n, films, weights, fromMs, aired)
        }
    }

    // The tail pool of [day]: its base (the day pool as built; on a repaired day the record's tail pool and what the
    // channel admits of the current selection, less what the record has airing before the change) without what the
    // new selection lacks, and what it takes of the added libraries. The caller keeps only items with a current row:
    // an item deleted since the cut-off is still in the snapshot, and a fresh tail never lays out one with no row.
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
        return days.tailPoolOf(s, base, present, taken, homesOf(s, snapshot), today, own)
    }

    // The one walk of the library: per distinct cut-off, the new selection, the current one when a day is repaired and
    // the removed libraries when [withRemoved], read together (one state), and split per selection as a day reads them.
    private suspend fun walk(
        cuts: List<Cut>,
        repaired: Boolean,
        withRemoved: Boolean,
    ): Walked {
        val selections = ArrayList<Set<ItemId>>(THREE)
        selections += change.after
        if (repaired) selections += change.before
        if (withRemoved) selections += removed
        val walkClock = WalkClock()
        val raw = snapshotsOf(library, account, selections, cuts, walkClock)
        if (walkClock.txNanos >= 0) clock?.addWalkTx(walkClock.txNanos)

        fun readOf(index: Int): Read {
            val (plain, filtered) = splitByExtras(raw.mapValues { (_, snapshots) -> snapshots[index] }, cuts)
            return Read(plain, filtered)
        }
        val after = readOf(0)
        val before = if (repaired) readOf(1) else null
        // The removed libraries' snapshots are plain whatever the cut: the pin names a library, no extras filter.
        val removedAt = if (withRemoved) raw.mapValues { (_, snapshots) -> snapshots.last() } else emptyMap()
        return Walked(after, before, removedAt)
    }

    // Whether the block on air at the change in [day] (a day shown at the change) is a programme: only then is there a
    // pinned programme, whose library may be a removed one.
    private fun programmeOnAir(day: ChannelDay): Boolean =
        day.day.blocks
            .first { change.atMs >= it.startMs && change.atMs < it.endMs }
            .content is ScheduleContent.Program

    // What each channel admits of the current selection, from the walk's snapshots of it.
    private fun admittedBefore(scheduled: List<Scheduled>): Admitted {
        val before = checkNotNull(walked.before)
        return days.admitted(held.lineup, scheduled, before.plain, before.filtered, change.before)
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
        // Read in the walk when a programme is on air; a read of its own otherwise, as before the walk.
        return removedSnapshots[cutoffMs]
            ?: library.ruleSnapshot(account, removed, Instant.fromEpochMilliseconds(cutoffMs)).also {
                removedSnapshots[cutoffMs] = it
            }
    }
}

private const val THREE = 3

/** [this] block as the record keeps it. */
internal fun ScheduleBlock.kept(): KeptBlock =
    when (val c = content) {
        is ScheduleContent.Program -> KeptBlock(startMs, endMs, c.itemId, c.mediaStartOffsetMs)
        ScheduleContent.Slate -> KeptBlock(startMs, endMs, null, 0)
    }
