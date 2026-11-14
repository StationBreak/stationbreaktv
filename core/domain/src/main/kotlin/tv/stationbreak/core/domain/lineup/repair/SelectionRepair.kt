// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.WalkClock
import tv.stationbreak.core.domain.lineup.Admitted
import tv.stationbreak.core.domain.lineup.BuildProbes
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
import java.util.concurrent.atomic.AtomicReference

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
    // Times the steps (the timing line's), when given; it changes nothing. Only the main coroutine records into it.
    private val clock: RepairClock? = null,
    // The thread CPU clock a build lane reads, only when there is a [clock].
    private val probes: BuildProbes = BuildProbes(),
) {
    private val fills = Fills(library, account)
    private val removed = change.before - change.after
    private val added = (change.after - change.before).mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.value) }
    private val homes = HashMap<Pair<Cut, DayCuration>, Homes?>()

    /** How many ids the tail pools' current-row read had to ask for beyond the early read's (a guard; 0 expected). */
    internal val primeMisses: Int get() = fills.primeMisses

    // A selection's snapshots as a day reads them: the plain ones, and for the cuts the extras filter applies to, the
    // filtered ones (each by cut-off).
    private class Read(
        val plain: Map<Long, RuleSnapshot>,
        val filtered: Map<Long, RuleSnapshot>,
    )

    // What one walk of the library gave: the new selection (always), the current selection (when a day is repaired,
    // taken once by the lane that builds from it, so its snapshots are let go as soon as that build is made), the
    // removed libraries' plain snapshots by cut-off (when a pinned programme may need them) and the walk's transaction
    // time.
    private class Walked(
        val after: Read,
        before: Read?,
        val removed: Map<Long, RuleSnapshot>,
        val txNanos: Long,
    ) {
        private val current = AtomicReference(before)

        fun takeBefore(): Read = checkNotNull(current.getAndSet(null)) { "the current selection was read once" }
    }

    // A lane's result with the lane's own wall time and, for a build, its thread's CPU time (-1: not measured).
    private class Timed<T>(
        val value: T,
        val wallNanos: Long,
        val cpuNanos: Long,
    )

    // The early read: which items of the new selection's snapshots have a current row, and the library's
    // contentGeneration read just before it.
    private class Early(
        val generation: Long,
        val asked: Set<CanonicalId>,
        val found: Set<CanonicalId>,
    )

    // The lanes of one plan, started together. Each reads the walk's snapshots, and nothing else of the plan.
    private class Lanes(
        val walk: Once<Walked>,
        val after: Once<Admitted>,
        val before: Once<Admitted>?,
        val early: Once<Early>,
    )

    // A lane awaited by the main coroutine, once: the wait is a step of its own (LanesWait) and the lane's wall time
    // (and CPU time, for a build) is recorded then. A lane never touches the clock.
    private class Once<T>(
        private val lane: Deferred<Timed<T>>,
        private val clock: RepairClock?,
        private val phase: RepairPhase,
        private val cpu: Boolean = false,
    ) {
        private var done: Timed<T>? = null

        suspend fun get(): T {
            done?.let { return it.value }
            val result = clock.time(RepairPhase.LanesWait) { lane.await() }
            done = result
            clock?.lane(phase, result.wallNanos)
            if (cpu) clock?.addBuildCpu(result.cpuNanos)
            return result.value
        }
    }

    // Per snapshot of the new selection (by cut-off and extras): every item id it holds, and those it adds.
    private val presentBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()
    private val addedBy = HashMap<Pair<Long, Boolean>, Set<CanonicalId>>()

    /**
     * The repairs of [today] (the days on air at the change, under the selection before it). The library reads and the
     * two builds run in lanes (#684) on whatever dispatcher this runs on (the planning pool): the one walk, what each
     * channel admits of the new selection and (on a repaired day) of the current one, and one early read of which items
     * of the new selection's snapshots have a current row. Every step of the plan is on the main coroutine, in the
     * order of the plan: kept windows, titles (the current build), tail pools (the early read's answers, the walk),
     * tails (the new build). Nothing a lane produces is read before it is awaited, and no lane writes a field.
     */
    suspend fun plan(today: List<ChannelDay>): PlannedRepair {
        val atMs = change.atMs
        val scheduled = days.scheduled(held.lineup, atMs, held.meta.extrasFromMs)
        val byId = scheduled.associateBy { it.channel.id }
        val pairs = today.mapNotNull { day -> byId[day.channel.id]?.let { day to it } }
        // With no day to repair nothing reads what the lanes read.
        if (pairs.isEmpty()) return PlannedRepair(change.seq, emptyList(), emptyList(), emptyList())
        val shown = pairs.filter { (day, _) -> day.hiddenAt(atMs) == null }
        val repaired = pairs.any { (day, _) -> day.record?.repair != null }
        // A removed library is read only to name the library of a pinned programme, which only a day with a programme
        // on air at the change has.
        val withRemoved = removed.isNotEmpty() && shown.any { (day, _) -> programmeOnAir(day) }
        return coroutineScope {
            val lanes = launchLanes(scheduled, repaired, withRemoved)
            // Each day's kept window first (nothing of it needs a lane).
            val windows = ArrayList<KeptWindow?>(pairs.size)
            clock.time(RepairPhase.Kept) {
                fills.live(shown.flatMap { (day, _) -> KeptWindow.programmesOf(day, atMs) })
            }
            for ((day, _) in pairs) {
                // A day hidden at the change has nothing on the air to keep.
                windows +=
                    clock.time(RepairPhase.Kept) {
                        if (day.hiddenAt(atMs) == null) KeptWindow.of(day, atMs, change.seq, fills) else null
                    }
            }
            val before = lanes.before?.get()
            val titles = HashSet<CanonicalId>()
            for ((day, _) in pairs) titles += ownTitles(day, before)
            // The early read's answers, taken once, after the last kept window's own reads.
            val early = lanes.early.get()
            fills.prime(early.asked, early.found)
            val walked = lanes.walk.get()
            if (walked.txNanos >= 0) clock?.addWalkTx(walked.txNanos)
            // With nothing added no day takes anything from the new build, so its tail pools do not wait for it.
            val taking = if (added.isEmpty()) null else lanes.after.get()
            val (prepared, live) = tailPools(pairs, windows, titles, walked, before, taking, early)
            val admitted = lanes.after.get()
            val repairs = ArrayList<ChannelRepair>()
            val fixes = ArrayList<LostFix>()
            for (p in prepared) {
                repairs += repairOf(p, live, admitted)
                p.kept?.let { fixes += it.fixes }
            }
            val pins = repairs.mapNotNull { r -> r.pinLibrary?.takeIf { it in removed }?.let { Pin(it, r.pinEndMs) } }
            PlannedRepair(change.seq, repairs, fixes, pins)
        }
    }

    // Each day's tail pool, then one read of which of their items have a current row, for every channel at once: the
    // early read's answers, unless a sync landed since (the content generation moved), when they are let go and the
    // read is today's.
    @Suppress("LongParameterList") // What the main path has by now.
    private suspend fun tailPools(
        pairs: List<Pair<ChannelDay, Scheduled>>,
        windows: List<KeptWindow?>,
        titles: Set<CanonicalId>,
        walked: Walked,
        before: Admitted?,
        taking: Admitted?,
        early: Early,
    ): Pair<List<Prepared>, Set<CanonicalId>> {
        val prepared = ArrayList<Prepared>(pairs.size)
        for ((index, pair) in pairs.withIndex()) {
            prepared += prepare(pair.first, pair.second, windows[index], titles, walked, before, taking)
        }
        val ids = HashSet<CanonicalId>()
        for (p in prepared) p.pool?.mapTo(ids) { it.id }
        var live = clock.time(RepairPhase.Pools) { fills.live(ids) }
        if (library.contentGeneration(account) != early.generation) {
            fills.dropPrimed()
            live = clock.time(RepairPhase.Pools) { fills.live(ids) }
        }
        return prepared to live
    }

    // Starts the lanes, with no context of their own: they run on the dispatcher this plan runs on (the planning pool).
    private fun CoroutineScope.launchLanes(
        scheduled: List<Scheduled>,
        repaired: Boolean,
        withRemoved: Boolean,
    ): Lanes {
        val cuts = scheduled.map { it.cut }.distinct()
        val walk = async { timed { walk(cuts, repaired, withRemoved) } }
        val after =
            async {
                val read = walk.await().value.after
                timedBuild { days.admitted(held.lineup, scheduled, read.plain, read.filtered, change.after) }
            }
        val before =
            if (repaired) {
                async {
                    val read = walk.await().value.takeBefore()
                    timedBuild { days.admitted(held.lineup, scheduled, read.plain, read.filtered, change.before) }
                }
            } else {
                null
            }
        val early = async { timed { earlyLive(walk.await().value.after) } }
        return Lanes(
            Once(walk, clock, RepairPhase.Snaps),
            Once(after, clock, RepairPhase.Admitted, cpu = true),
            before?.let { Once(it, clock, RepairPhase.Before, cpu = true) },
            Once(early, clock, RepairPhase.Live),
        )
    }

    // [block] with its wall time (0 with no clock): a lane's own work.
    private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
        val mark = clock?.ticks?.markNow()
        val value = block()
        return Timed(value, mark?.elapsedNow()?.inWholeNanoseconds ?: 0L, -1L)
    }

    // A build [block] with its wall time and its thread's CPU time. The block does not suspend, so it stays on one
    // thread and the thread's CPU time over it is the build's.
    private fun <T> timedBuild(block: () -> T): Timed<T> {
        val mark = clock?.ticks?.markNow()
        val start = if (clock == null) -1L else probes.threadCpuNanos()
        val value = block()
        val end = if (clock == null) -1L else probes.threadCpuNanos()
        val spent = if (start < 0 || end < start) -1L else end - start
        return Timed(value, mark?.elapsedNow()?.inWholeNanoseconds ?: 0L, spent)
    }

    // The early read: which items of the new selection's snapshots (plain and filtered) have a current row, with the
    // generation read just before it. Every tail pool item is in the snapshot its day reads, so this answers the tail
    // pools' own read (`Fills.prime`; a miss still reads).
    private suspend fun earlyLive(after: Read): Early {
        val generation = library.contentGeneration(account)
        val asked = HashSet<CanonicalId>()
        for (snapshot in after.plain.values + after.filtered.values) snapshot.items.mapTo(asked) { it.item.id }
        val found = if (asked.isEmpty()) emptySet() else library.currentIds(account, asked)
        return Early(generation, asked, found)
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
        val removedBy: Map<Long, RuleSnapshot>,
    )

    // [day] made ready for its repair: with no [kept] window (a day hidden at the change) its tail starts at the half
    // hour.
    @Suppress("LongParameterList") // The day, its window, and what the lanes gave.
    private suspend fun prepare(
        day: ChannelDay,
        s: Scheduled,
        kept: KeptWindow?,
        today: Set<CanonicalId>,
        walked: Walked,
        before: Admitted?,
        after: Admitted?,
    ): Prepared {
        // The snapshot of the new selection that [s]'s day reads (with the extras out, for a filtered day).
        val read = walked.after
        val snapshot = (if (s.extras) read.filtered else read.plain).getValue(s.cutoffMs)
        val fromMs = kept?.fromMs ?: maxOf(day.epochStartMs, change.atMs - Math.floorMod(change.atMs, KEPT_STEP_MS))
        val tailFromMs = kept?.pinEndMs ?: fromMs
        val pool =
            if (tailFromMs >= day.epochEndMs) {
                null
            } else {
                clock.time(RepairPhase.Pools) { tailPool(day, s, snapshot, before, after, today) }
            }
        return Prepared(day, s, snapshot, kept, fromMs, tailFromMs, pool, walked.removed)
    }

    // [p]'s repair, its tail pool kept to the items in [live]. When the tail holds nothing either, a day hidden at the
    // change is one slate all day, hidden with the new selection's reason.
    private fun repairOf(
        p: Prepared,
        live: Set<CanonicalId>,
        after: Admitted,
    ): ChannelRepair {
        val day = p.day
        val s = p.s
        val snapshot = p.snapshot
        val kept = p.kept
        val fromMs = p.fromMs
        val pool = p.pool?.filter { it.id in live }
        val tail = tailOf(day, s, snapshot, pool, p.tailFromMs, kept?.aired.orEmpty(), after)
        val nothing = tail.isNotEmpty() && tail.none { it.content is ScheduleContent.Program }
        // Hidden at the change and after it: hidden all day, from its start, so no window ever shows it.
        val offAir = kept == null && nothing
        val start = if (offAir) day.epochStartMs else fromMs
        val blocks =
            if (offAir) {
                listOf(KeptBlock(day.epochStartMs, day.epochEndMs, KeptContent.Slate))
            } else {
                kept?.blocks.orEmpty() + tail.map { it.kept() }
            }
        val reason = after.days[day.channel.id]?.hidden ?: day.hiddenAt(change.atMs) ?: HiddenReason.EmptyToday
        return ChannelRepair(
            channel = day.channel.id,
            n = day.n,
            dayEndMs = day.epochEndMs,
            atMs = change.atMs,
            seq = change.seq,
            libraries = change.after,
            keptFromMs = start,
            pinEndMs = kept?.pinEndMs ?: start,
            pinLibrary = clock.time(RepairPhase.Pin) { kept?.pinItem?.let { libraryOf(it, s, snapshot, p.removedBy) } },
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
        after: Admitted,
    ): List<ScheduleBlock> {
        if (pool == null) return emptyList()
        val repaired = day.record?.repair != null
        return clock.time(RepairPhase.Layout) {
            val (films, weights) = sidesOf(day, pool, repaired, addedIn(s, snapshot), after)
            layoutTail(day.settings, pool, day.n, films, weights, fromMs, aired)
        }
    }

    // The tail pool of [day]: its base (the day pool as built; on a repaired day the record's tail pool and what the
    // channel admits of the current selection, less what the record has airing before the change) without what the
    // new selection lacks, and what it takes of the added libraries. The caller keeps only items with a current row:
    // an item deleted since the cut-off is still in the snapshot, and a fresh tail never lays out one with no row.
    @Suppress("LongParameterList") // The day, the snapshot, the two builds and today's titles.
    private suspend fun tailPool(
        day: ChannelDay,
        s: Scheduled,
        snapshot: RuleSnapshot,
        before: Admitted?,
        after: Admitted?,
        today: Set<CanonicalId>,
    ): List<ScheduleItem> {
        val repaired = day.record?.repair != null
        val dayPool = fills.dayPool(day)
        val base =
            if (repaired) {
                val aired = HashSet<CanonicalId>()
                for (block in checkNotNull(day.record?.repair).blocks) {
                    if (block.startMs < change.atMs) block.programId()?.let { aired += it }
                }
                // The record keeps only the tail's items: the rest of the day's pool comes from what the channel
                // admits now, so a second change can lay out what the first tail did not.
                val now = before?.pool(day.channel.id).orEmpty()
                val union = LinkedHashMap<CanonicalId, ScheduleItem>()
                for (item in dayPool) union[item.id] = item
                for (item in now) union.putIfAbsent(item.id, item)
                union.values.filter { it.id !in aired }
            } else {
                dayPool
            }
        val present = presentBy.getOrPut(s.cutoffMs to s.extras) { snapshot.items.mapTo(HashSet()) { it.item.id } }
        val addedIds = addedIn(s, snapshot)
        // The new build is awaited only when the change adds a library: with none, nothing is taken.
        val taken = after?.pool(day.channel.id)?.filter { it.id in addedIds }.orEmpty()
        val own = ownTitles(day, before)
        return days.tailPoolOf(s, base, present, taken, homesOf(s, snapshot), today, own)
    }

    // The one walk of the library: per distinct cut-off, the new selection, the current one when a day is repaired and
    // the removed libraries when [withRemoved], read together (one state), and split per selection as a day reads them.
    // It is a lane's work: it reports the walk's transaction time in what it returns, to be recorded by the main
    // coroutine.
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

        fun readOf(index: Int): Read {
            val (plain, filtered) = splitByExtras(raw.mapValues { (_, snapshots) -> snapshots[index] }, cuts)
            return Read(plain, filtered)
        }
        val after = readOf(0)
        val before = if (repaired) readOf(1) else null
        // The removed libraries' snapshots are plain whatever the cut: the pin names a library, no extras filter.
        val removedAt = if (withRemoved) raw.mapValues { (_, snapshots) -> snapshots.last() } else emptyMap()
        return Walked(after, before, removedAt, walkClock.txNanos)
    }

    // Whether the block on air at the change in [day] (a day shown at the change) is a programme: only then is there a
    // pinned programme, whose library may be a removed one.
    private fun programmeOnAir(day: ChannelDay): Boolean =
        day.day.blocks
            .first { change.atMs >= it.startMs && change.atMs < it.endMs }
            .content is ScheduleContent.Program

    // The titles of [day]'s pool today: its day pool, and on a repaired day what the channel admits now as well.
    private suspend fun ownTitles(
        day: ChannelDay,
        before: Admitted?,
    ): Set<CanonicalId> {
        val out = fills.dayPool(day).mapTo(HashSet()) { it.groupId }
        if (day.record?.repair != null) before?.pool(day.channel.id)?.mapTo(out) { it.groupId }
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
        admitted: Admitted,
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

    // The library of the pinned programme [item]: the new selection's, or else a removed library's (from the walk).
    private fun libraryOf(
        item: CanonicalId,
        s: Scheduled,
        snapshot: RuleSnapshot,
        removedBy: Map<Long, RuleSnapshot>,
    ): ItemId? {
        val found =
            snapshot.items.firstOrNull { it.item.id == item }?.libraryId
                ?: removedAt(s.cutoffMs, removedBy)?.items?.firstOrNull { it.item.id == item }?.libraryId
                ?: return null
        return (change.before + change.after).firstOrNull { CanonicalId.parseOrNull(it.value) == found }
            ?: ItemId(found.value)
    }

    // The removed libraries as held before [cutoffMs], from the walk (a pinned programme means a programme was on
    // air, so the walk read them); null when the change removes none.
    private fun removedAt(
        cutoffMs: Long,
        removedBy: Map<Long, RuleSnapshot>,
    ): RuleSnapshot? {
        if (removed.isEmpty()) return null
        return checkNotNull(removedBy[cutoffMs]) { "the walk read the removed libraries" }
    }
}

private const val THREE = 3

/** [this] block as the record keeps it. */
internal fun ScheduleBlock.kept(): KeptBlock =
    when (val c = content) {
        is ScheduleContent.Program -> KeptBlock(startMs, endMs, KeptContent.Program(c.itemId, c.mediaStartOffsetMs))
        ScheduleContent.Slate -> KeptBlock(startMs, endMs, KeptContent.Slate)
        ScheduleContent.Break -> KeptBlock(startMs, endMs, KeptContent.Break)
    }
