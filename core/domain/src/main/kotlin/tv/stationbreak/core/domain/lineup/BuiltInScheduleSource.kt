// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The [ScheduleSource] built into the app (spec 12): it reads the lineup from [service] and the library from
 * [library], and works the days out with the engine and the scheduler. Whose lineup and which libraries are not asked
 * of the caller: the account is [LibraryRepository.activeAccount] and the libraries are what [libraries] gives, the
 * same two the `LineupRefresher` uses.
 *
 * Every switched-on channel's day is built from the library as held before that day's cut-off, with **one
 * `ruleSnapshot` per distinct cut-off and never `scheduleItems`** (#275). The starter and leftovers channels all share
 * the lineup's one offset and day start, so a lineup of them is one read; a custom channel with another day start adds
 * one. The built days (settings, epoch and day pool) are kept under what they depend on, not the lineup revision: the
 * lineup's day inputs ([LineupDays.inputsOf]), the content generation the lineup was planned at
 * ([RefreshMeta.plannedGeneration]) and the cut-offs. So asking again within the same days reads nothing, and an edit
 * that changes no pool (a renumber, a swap on one side of the Kids band) builds nothing: the kept days are handed out
 * with the current lineup's channels, numbers and names ([LineupDays.rebound]), so what the guide, flipping and live
 * see follows the lineup at once. An edit that does change a pool (a switch on or off, a move into or out of the Kids
 * band that changes a policy) builds the days again. [onAir] builds a channel's day once (kept on the cached
 * `ChannelDay`) and reads the names of the blocks it returns with one `programInfo` call; the days themselves stay
 * internal to this class.
 *
 * Callers that miss the cache together share one build (single flight, #392): the first starts it in this source's own
 * scope and the rest await it, so a caller that is cancelled (a flip superseded mid-lookup) leaves the build running
 * for the others, and for the cache. A build that fails is not kept; the next caller builds again.
 *
 * The snapshots are kept apart from the days ([SnapshotCache]): days built again (a refresh after the library changed,
 * or an edit that changes a pool) read the library only if it changed (its content generation moved). A build whose
 * key is no longer the current lineup's stops at its next channel and does not report; the callers that joined it ask
 * again and get the current lineup's days. One whose lineup moved on to a revision of the same key goes on.
 *
 * [timed] is told the build of each following day that [onAir] needed: the day's index and the milliseconds it took
 * (numbers only; no item, title or URL ever reaches it), so a first tune near a day edge can be measured. [built] is
 * told every build as it completes, with where its time went ([DayBuild]; numbers only), and [laidOut] each channel
 * day's schedule layout (worked out once, on first use; [warm] does it ahead).
 *
 * [schedule] gives one window of every visible channel's airings. With [ScheduleBuild.CachedOnly] it reads only kept
 * days: it never builds, never joins a build in flight (a warmer's reads as not built) and never reads a snapshot.
 * What it can still wait for is the store, on a cold lineup (`LineupService.loaded`), and the layout of the day that
 * holds the start, which it works out if needed. A following day whose layout is not yet worked out counts as not
 * cached, so the window comes back incomplete rather than slow.
 *
 * With [persisted], the days outlive the process (#480): a build first looks for them on disk under a key of everything
 * they depend on (see [PersistedDays]), and takes them from there when it matches (`DayBuild` says `snapshot=disk`,
 * and no snapshot is read). It looks on the process's first build of a set of cut-offs, and again only when their
 * snapshots are no longer held. A cold build writes its days there afterwards, in this source's scope and on
 * [saveDispatcher] (background priority), without holding up the callers. A miss of any kind is an ordinary build.
 *
 * Main-safe: [airings], [onAir], [schedule] and [standIns] do their work on [dispatcher] (default:
 * `Dispatchers.Default`), so building a day never runs on, or blocks, the caller's thread.
 */
@Suppress(
    "TooManyFunctions", // The one source of the days: its reads, builds and warm-up stay together.
    "LongParameterList", // Its three timing hooks are numbers-only and each defaulted.
)
class BuiltInScheduleSource(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val timed: (dayIndex: Long, buildMs: Long) -> Unit = { _, _ -> },
    private val built: (DayBuild) -> Unit = {},
    laidOut: (dayIndex: Long, layoutMs: Long) -> Unit = { _, _ -> },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val probes: BuildProbes = BuildProbes(),
    private val persisted: PersistedDays? = null,
    private val saveDispatcher: CoroutineDispatcher = BackgroundSaves.dispatcher,
    private val libraries: suspend () -> Set<ItemId>?,
) : ScheduleSource {
    // What a set of built days depends on (see the class comment): never the revision, nor any number or name that
    // changes no pool. [planned] stands for the library's state: a refresh after a change records a new one.
    private data class Key(
        val account: LibraryAccount,
        val libraries: Set<ItemId>,
        val inputs: String,
        val planned: Long?,
        val cutoffs: List<Long>,
    )

    private val days = LineupDays(service.catalog) { n, ms -> laidOut(n, ms) }
    private val lock = Any()

    // Access-ordered: the set least recently asked for is the one evicted.
    private val kept = LinkedHashMap<Key, List<ChannelDay>>(KEPT, 0.75f, true)
    private val inFlight = HashMap<Key, Deferred<List<ChannelDay>>>()

    /** For tests: how many callers joined a build another caller had started, and how many builds are in flight. */
    internal val joined = AtomicInteger()

    internal fun inFlightCount(): Int = synchronized(lock) { inFlight.size }

    // Builds belong to the source, not to the caller that started one: see the class comment.
    private val buildScope = CoroutineScope(SupervisorJob() + dispatcher)

    // Snapshots outlive the lineup revision they were first read for: see [SnapshotCache].
    private val snapshots = SnapshotCache(buildScope)

    // The writes of built days to disk still running; each removes itself when done.
    private val saves = HashSet<Job>()

    // The (account, libraries, cut-offs) this process has already looked for on disk: see [shouldLook].
    private val diskLooked = HashSet<Triple<LibraryAccount, Set<ItemId>, List<Long>>>()

    // The last lineup's day inputs: worked out once per lineup, not on every lookup.
    private var lastInputs: Pair<Lineup, String>? = null

    /** For tests: waits for the writes of built days to disk started so far. */
    internal suspend fun awaitSaves() = synchronized(lock) { saves.toList() }.joinAll()

    /** For tests: how many snapshot reads the cache has started. */
    internal fun snapshotReads(): Int = snapshots.reads

    /** For tests: how many snapshot reads the cache has cancelled. */
    internal fun cancelledSnapshotReads(): Int = snapshots.cancelledReads

    /** The build of days the account's current lineup no longer has stops, and its callers go on. */
    private class StaleBuild : RuntimeException("The lineup moved on while the day build ran")

    private fun isCurrent(
        account: LibraryAccount,
        revision: Long,
    ): Boolean = service.lineup(account).value?.revision == revision

    private fun inputsOf(lineup: Lineup): String {
        synchronized(lock) { lastInputs?.takeIf { it.first === lineup }?.let { return it.second } }
        val inputs = days.inputsOf(lineup)
        synchronized(lock) { lastInputs = lineup to inputs }
        return inputs
    }

    private fun keyOf(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        held: Revisioned,
        scheduled: List<Scheduled>,
    ): Key {
        val cutoffs = scheduled.map { it.cutoffMs }.distinct().sorted()
        return Key(account, libraries, inputsOf(held.lineup), held.meta.plannedGeneration, cutoffs)
    }

    /**
     * [built] as [found]'s lineup shows them. Kept so in place of [built], so the next caller need not do it again, and
     * so that callers of one lineup get the one list.
     */
    private fun bound(
        found: Lookup,
        built: List<ChannelDay>,
    ): List<ChannelDay> {
        if (days.isShownAs(built, found.scheduled)) return built
        val bound = days.rebound(built, found.scheduled)
        return synchronized(lock) {
            val now = kept[found.key]
            when {
                now === built -> bound.also { kept[found.key] = it }
                now != null && days.isShownAs(now, found.scheduled) -> now
                else -> bound
            }
        }
    }

    /** The days kept for [found], as its lineup shows them; null when none are kept. */
    private fun keptFor(found: Lookup): List<ChannelDay>? {
        val built = synchronized(lock) { kept[found.key] }
        return built?.let { bound(found, it) }
    }

    override suspend fun airings(at: Instant): List<ChannelAiring> =
        withContext(dispatcher) {
            val (account, chosen) = signedIn() ?: return@withContext emptyList()
            channelDays(account, chosen, at).map { it.airing() }
        }

    private suspend fun signedIn(): Pair<LibraryAccount, Set<ItemId>>? {
        val account = library.activeAccount().first()
        val chosen = libraries()?.takeIf { it.isNotEmpty() }
        return if (account == null || chosen == null) null else account to chosen
    }

    override suspend fun onAir(
        channel: ChannelId,
        at: Instant,
        next: Int,
    ): ChannelOnAir? {
        require(next >= 0) { "Cannot ask for a negative number of next blocks" }
        return withContext(dispatcher) {
            val (account, chosen) = signedIn() ?: return@withContext null
            val today = channelDays(account, chosen, at).firstOrNull { it.channel.id == channel && it.hidden == null }
            today?.let { onAirOf(account, chosen, it, at, next) }
        }
    }

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        require(until > from) { "The window must end after it starts" }
        return withContext(dispatcher) {
            val (account, chosen) = signedIn() ?: return@withContext null
            // Pinned to the lineup revision of this first lookup: every day of the window must be of that revision.
            val first = lookup(account, chosen, from)
            val days =
                when {
                    first == null -> null
                    build == ScheduleBuild.CachedOnly -> keptFor(first)
                    else -> channelDays(account, chosen, from)
                }
            when {
                // No lineup: nothing to build, so AsNeeded is complete and empty; CachedOnly lacks the day.
                first == null -> {
                    ScheduleWindow(emptyList(), complete = build == ScheduleBuild.AsNeeded)
                }

                days == null -> {
                    ScheduleWindow(emptyList(), complete = false)
                }

                else -> {
                    val reading = WindowRead(account, chosen, build, first.held.revision)
                    windowOf(reading, days.filter { it.hidden == null }, from, until)
                }
            }
        }
    }

    /** What one [schedule] call reads: the following days it resolved (by start) and whether the lineup stayed put. */
    private class WindowRead(
        val account: LibraryAccount,
        val chosen: Set<ItemId>,
        val build: ScheduleBuild,
        val revision: Long,
    ) {
        val following = HashMap<Long, List<ChannelDay>?>()
        var consistent = true
    }

    private suspend fun windowOf(
        reading: WindowRead,
        visible: List<ChannelDay>,
        from: Instant,
        until: Instant,
    ): ScheduleWindow {
        val fromMs = from.toEpochMilliseconds()
        val untilMs = until.toEpochMilliseconds()
        val laid =
            visible.map { today ->
                // The end of the day after the one that holds `from`, in the channel's own epoch length (as onAir).
                val builtUntilMs = today.epochStartMs + 2 * today.settings.epochMs
                val limit = minOf(untilMs, builtUntilMs)
                val own = today.day.blocks.filter { it.endMs > fromMs && it.startMs < limit }
                Triple(today, builtUntilMs, own + blocksUntil(reading, today, limit))
            }
        val ids =
            laid.flatMapTo(HashSet()) { (_, _, blocks) ->
                blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }
            }
        val info = if (ids.isEmpty()) emptyMap() else library.programInfo(reading.account, ids)
        // A lineup that moved on while the window was read may have given days of two revisions.
        if (!isCurrent(reading.account, reading.revision)) reading.consistent = false
        var complete = reading.consistent
        val rows =
            laid.map { (today, builtUntilMs, blocks) ->
                // A row that stops short of the window (or of what is built) lacks a day: not built, kept or laid out.
                if ((blocks.lastOrNull()?.endMs ?: fromMs) < minOf(untilMs, builtUntilMs)) complete = false
                ChannelRow(today.airing(), blocks.map { it.toAiring(info) }, builtUntilMs)
            }
        return ScheduleWindow(rows, complete)
    }

    /**
     * The blocks of the days that follow [from]'s day, up to [limitMs] (the blocks that start before it). With
     * [ScheduleBuild.CachedOnly] only kept days whose layout is already worked out are read, and the blocks stop at the
     * first that is not; otherwise each day is built as [followingBlocks] builds it. Fewer if the channel stops airing.
     * It does not report to `timed`: that line counts only the builds a tune waited for.
     */
    private suspend fun blocksUntil(
        reading: WindowRead,
        from: ChannelDay,
        limitMs: Long,
    ): List<ScheduleBlock> {
        val blocks = ArrayList<ScheduleBlock>()
        var day = from
        while (day.day.endMs < limitMs) {
            day = followingDay(reading, from.channel.id, day.day.endMs) ?: break
            blocks += day.day.blocks.filter { it.startMs < limitMs }
        }
        return blocks
    }

    /** The day of [channel] that starts at [startMs], or null if there is none to read (see [blocksUntil]). */
    private suspend fun followingDay(
        reading: WindowRead,
        channel: ChannelId,
        startMs: Long,
    ): ChannelDay? {
        val day = followingDays(reading, startMs)?.firstOrNull { it.channel.id == channel }
        // A kept day still being laid out would make this call do that work: it counts as not cached.
        return day?.takeUnless { reading.build == ScheduleBuild.CachedOnly && !it.isLaidOut }
    }

    /**
     * The days that start at [startMs], once per call however many rows ask, of the revision the call began with: a
     * newer revision marks the reading inconsistent and gives nothing.
     */
    private suspend fun followingDays(
        reading: WindowRead,
        startMs: Long,
    ): List<ChannelDay>? {
        if (reading.following.containsKey(startMs)) return reading.following[startMs]
        val start = Instant.fromEpochMilliseconds(startMs)
        val found = lookup(reading.account, reading.chosen, start)
        val days =
            when {
                found == null -> null
                found.held.revision != reading.revision -> null.also { reading.consistent = false }
                reading.build == ScheduleBuild.CachedOnly -> keptFor(found)
                else -> channelDays(reading.account, reading.chosen, start)
            }
        reading.following[startMs] = days
        return days
    }

    override suspend fun standIns(
        channel: ChannelId,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock> {
        require(block.airing is Airing.Program) { "Stand-ins are asked for the scheduled block, not ${block.airing}" }
        return withContext(dispatcher) {
            val (account, chosen) = signedIn() ?: return@withContext emptyList()
            val at = Instant.fromEpochMilliseconds(block.startMs)
            val day = channelDays(account, chosen, at).firstOrNull { it.channel.id == channel }
            day?.let { standInsOf(account, it, block, exclude) }.orEmpty()
        }
    }

    private suspend fun standInsOf(
        account: LibraryAccount,
        day: ChannelDay,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock> {
        val ids = day.pool.mapTo(HashSet()) { it.id }
        // A pool item with no current row is gone from the library: never a stand-in (a liveness read, not names).
        val live = if (ids.isEmpty()) emptySet() else library.currentIds(account, ids)
        val barred = exclude + (ids - live)
        val length = block.endMs - block.startMs
        val picked = StandIn.pick(day.settings, day.n, day.pool, block.startMs, length, barred)
        val info = if (picked.isEmpty()) emptyMap() else library.programInfo(account, picked.mapTo(HashSet()) { it.id })
        var cursor = block.startMs
        val laid = ArrayList<AiringBlock>()
        // A current row that names nothing (not a movie or an episode) cannot be shown, so it is skipped.
        for (item in picked.filter { it.id in info }) {
            if (cursor >= block.endMs) break
            val named = info.getValue(item.id)
            val end = minOf(cursor + item.runtimeMs, block.endMs)
            laid += AiringBlock(cursor, end, Airing.StandIn(item.id, named, block.startMs))
            cursor = end
        }
        return laid
    }

    private suspend fun onAirOf(
        account: LibraryAccount,
        chosen: Set<ItemId>,
        today: ChannelDay,
        at: Instant,
        next: Int,
    ): ChannelOnAir {
        val now = today.day.whatsOn(at.toEpochMilliseconds(), next)
        // The end of the day after the one that holds `at`, in the channel's own epoch length.
        val builtUntilMs = today.epochStartMs + 2 * today.settings.epochMs
        val following = followingBlocks(account, chosen, today, next - now.next.size, builtUntilMs)
        val blocks = listOf(now.block) + now.next + following
        val ids = blocks.mapNotNullTo(HashSet()) { (it.content as? ScheduleContent.Program)?.itemId }
        val info = if (ids.isEmpty()) emptyMap() else library.programInfo(account, ids)
        val airings = blocks.map { it.toAiring(info) }
        return ChannelOnAir(today.airing(), airings.first(), airings.drop(1), builtUntilMs)
    }

    /**
     * Up to [count] blocks after [from]'s day, from the days that follow it, each built from its own snapshot. Fewer if
     * the channel stops airing, or if the days run to [builtUntilMs] (the end of day n + 1): nothing is built past it.
     */
    private suspend fun followingBlocks(
        account: LibraryAccount,
        chosen: Set<ItemId>,
        from: ChannelDay,
        count: Int,
        builtUntilMs: Long,
    ): List<ScheduleBlock> {
        val blocks = ArrayList<ScheduleBlock>()
        var day = from
        while (blocks.size < count && day.day.endMs < builtUntilMs) {
            val start = Instant.fromEpochMilliseconds(day.day.endMs)
            val (days, builtInMs) = channelDaysTimed(account, chosen, start)
            day = days.firstOrNull { it.channel.id == from.channel.id } ?: break
            val layout = day.day
            if (builtInMs != null) timed(day.n, builtInMs)
            val ahead = layout.whatsOn(layout.startMs, count - blocks.size - 1)
            blocks += ahead.block
            blocks += ahead.next
        }
        return blocks
    }

    /**
     * Builds the days that will be asked for soon, so that no tune has to wait for them (#392): the days at [now], and
     * the days that follow for every channel whose day ends within [WARM_AHEAD_MS], each with its schedule layout. A
     * caller who needs a day meanwhile joins the build, which runs at the normal priority like any other: starting an
     * hour ahead is what keeps it out of the way of a tune, not a lower priority (a tune waiting on a low-priority
     * build would be slower than one that built the day itself).
     * Returns when to look again (the soonest of those moments), or null when nothing is scheduled to warm.
     */
    internal suspend fun warm(now: Instant): Instant? {
        val (account, chosen) = signedIn() ?: return null
        val nowMs = now.toEpochMilliseconds()
        val today = channelDays(account, chosen, now)
        val ends = today.map { it.epochStartMs + it.settings.epochMs }
        val following =
            ends.filter { it - nowMs <= WARM_AHEAD_MS }.distinct().flatMap {
                channelDays(account, chosen, Instant.fromEpochMilliseconds(it))
            }
        // The layout of each day is lazy: work it out here, off the caller's path.
        withContext(dispatcher) {
            (today + following).forEach {
                // So that a cancelled warm stops here and a tune that starts meanwhile gets the thread.
                yield()
                it.day
            }
        }
        // A day already within the window is next looked at when it ends (the following day is then today's).
        val wake = ends.minOfOrNull { if (it - nowMs > WARM_AHEAD_MS) it - WARM_AHEAD_MS else it }
        return wake?.let { Instant.fromEpochMilliseconds(it) }
    }

    /** The switched-on channels' days at [at], in number order. */
    internal suspend fun channelDays(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): List<ChannelDay> = channelDaysTimed(account, libraries, at).first

    /**
     * The days at [at], and how long this call took to build them in milliseconds: null when they were already built
     * or another caller's build was joined (that build reports itself to [built]).
     */
    private suspend fun channelDaysTimed(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Pair<List<ChannelDay>, Long?> {
        // A build that finds the current lineup no longer has its days (its key) stops, and the callers that joined it
        // start again here, against the lineup that is current now; so does a caller whose lineup moved on while it
        // waited. Only a new revision can send a caller round: a change to the library does not (the snapshot reads are
        // shared, see SnapshotCache, and a build is given the state it asked for). The loop is not bounded, and cannot
        // be outrun: a pass round it needs a newer revision, revisions are published only by LineupService (one at a
        // time under its lock: a refresh when the library asks for one, and an edit for each press of the user's, so a
        // person's pace and not a loop's), and a build that finishes while its key is current is kept, so the next pass
        // is a cache hit. Each await in it is a cancellation point, so a caller that gives up leaves at once.
        while (true) {
            try {
                return channelDaysOnce(account, libraries, at)
            } catch (_: StaleBuild) {
                // Try again against the lineup that replaced it.
            }
        }
    }

    /** The lineup held for [account], the channels scheduled at [at] and the [Key] their days are kept under. */
    private data class Lookup(
        val held: Revisioned,
        val scheduled: List<Scheduled>,
        val key: Key,
    )

    /**
     * Works out which days [at] needs without reading any: null if there is no lineup. The one thing that can wait is
     * `LineupService.loaded`, which reads the store when the entry is cold.
     */
    private suspend fun lookup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Lookup? {
        val held = service.loaded(account) ?: return null
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds())
        return Lookup(held, scheduled, keyOf(account, libraries, held, scheduled))
    }

    private suspend fun channelDaysOnce(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Pair<List<ChannelDay>, Long?> {
        val found = lookup(account, libraries, at) ?: return emptyList<ChannelDay>() to null
        val key = found.key
        val began = TimeSource.Monotonic.markNow()
        var started = false
        val (hit, flight) =
            synchronized(lock) {
                val ready = kept[key]
                if (ready != null) {
                    ready to null
                } else {
                    null to
                        inFlight.getOrPut(key) {
                            started = true
                            lateinit var self: Deferred<List<ChannelDay>>
                            self =
                                buildScope.async(start = CoroutineStart.LAZY) {
                                    build(found, account, at.toEpochMilliseconds(), self)
                                }
                            self
                        }
                }
            }
        // Started outside the lock, once the entry it removes itself from is in place.
        if (started) flight?.start()
        if (!started && flight != null) joined.incrementAndGet()
        val built = hit ?: awaited(checkNotNull(flight))
        // A build that went on through a renumber gives days of the same key; they are shown as the lineup is now.
        if (!isCurrent(account, found.held.revision)) throw StaleBuild()
        return bound(found, built) to if (started) began.elapsedNow().inWholeMilliseconds else null
    }

    /**
     * The shared build's result. If the build itself was cancelled while this caller is still active, that is not this
     * caller's cancellation and must not be taken for it: it is reported as a failure of the build.
     */
    private suspend fun awaited(flight: Deferred<List<ChannelDay>>): List<ChannelDay> =
        try {
            flight.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) throw IllegalStateException("The day build was cancelled", e)
            throw e
        }

    @Suppress("LongMethod") // One build, timed phase by phase.
    private suspend fun build(
        wanted: Lookup,
        account: LibraryAccount,
        atMs: Long,
        self: Deferred<List<ChannelDay>>,
    ): List<ChannelDay> {
        val key = wanted.key
        val lineup = wanted.held.lineup
        val scheduled = wanted.scheduled
        try {
            val began = TimeSource.Monotonic.markNow()
            val job = currentCoroutineContext().job
            // A stale build stops at the next of these (a snapshot read goes on for the build that replaces it). A
            // newer revision with the same key (a renumber) is not stale: the build goes on, checked against that one.
            var revision = wanted.held.revision
            val checkpoint = {
                job.ensureActive()
                if (!isCurrent(account, revision)) revision = stillWanted(account, key, atMs) ?: throw StaleBuild()
            }
            val before = Probed(probes)
            val allocBefore = Reading(probes.allocatedBytes())
            // The generation is read before any snapshot (see [SnapshotCache]) and before the disk is looked at.
            val generation = library.contentGeneration(account)
            val disk =
                persisted?.takeIf { it.enabled }?.keyOf(
                    account,
                    key.libraries,
                    generation,
                    lineup,
                    key.inputs,
                    service.catalog.version,
                    scheduled,
                )
            val look = disk != null && shouldLook(account, key, generation)
            val restored = if (look) fromDisk(account, disk, scheduled, checkpoint) else null
            val diskMs = if (look) began.elapsedNow().inWholeMilliseconds else -1L
            if (restored != null) {
                keep(key, restored)
                reportDisk(scheduled, diskMs, restored.size, before, allocBefore)
                return restored
            }
            val snapshotsBegan = TimeSource.Monotonic.markNow()
            val outcomes = ArrayList<SnapshotCache.Found>()
            val read = snapshotsFor(account, key, generation, checkpoint, outcomes)
            val afterSnapshots = Probed(probes)
            val snapshotsMs = snapshotsBegan.elapsedNow().inWholeMilliseconds
            checkpoint()
            // days() never suspends, so it runs start to end on this one thread: its CPU time is that thread's.
            val threadBefore = probes.threadCpuNanos()
            var evaluations = 0
            val result = days.days(lineup, scheduled, read, checkpoint) { evaluations++ }
            val threadAfter = probes.threadCpuNanos()
            val totalMs = began.elapsedNow().inWholeMilliseconds
            keep(key, result)
            if (disk != null) save(account, disk, result)
            // Pools and leftovers claims; the schedule layout of each day is worked out on first use (see [laidOut]).
            val after = Probed(probes)
            val allocated = Reading(probes.allocatedBytes()) - allocBefore
            val found =
                when {
                    SnapshotCache.Found.Cleared in outcomes -> SnapshotCache.Found.Cleared
                    SnapshotCache.Found.Miss in outcomes -> SnapshotCache.Found.Miss
                    else -> SnapshotCache.Found.Hit
                }
            built(
                DayBuild(
                    scheduled.firstOrNull()?.n ?: -1,
                    totalMs,
                    snapshotsMs,
                    totalMs - snapshotsMs - maxOf(diskMs, 0),
                    result.size,
                    poolsCpuMs = threadCpuMs(threadBefore, threadAfter),
                    snapshotsProcCpuMs = afterSnapshots.cpuMs - before.cpuMs,
                    poolsProcCpuMs = after.cpuMs - afterSnapshots.cpuMs,
                    gcCount = after.gcCount - before.gcCount,
                    gcBlockMs = after.gcBlockMs - before.gcBlockMs,
                    snapshot = found.label,
                    evaluations = evaluations,
                    allocKb = if (allocated < 0) -1 else allocated / BYTES_PER_KB,
                    diskMs = diskMs,
                ),
            )
            return result
        } finally {
            // Only this build's own entry: a failed or cancelled build leaves nothing kept, so the next caller builds.
            synchronized(lock) { inFlight.remove(key, self) }
        }
    }

    /** The revision of [account]'s current lineup if its days at [atMs] are still [key]'s; null when they are not. */
    private fun stillWanted(
        account: LibraryAccount,
        key: Key,
        atMs: Long,
    ): Long? {
        val held = service.lineup(account).value ?: return null
        val same = keyOf(account, key.libraries, held, days.scheduled(held.lineup, atMs)) == key
        return if (same) held.revision else null
    }

    /**
     * Whether this build looks for its days on disk: on the process's first build of these cut-offs (for this account
     * and these libraries), or when [snapshots] no longer holds their snapshots. Otherwise a cold build costs only the
     * pool work, and a lineup edit does not pay a file read behind the store's lock. A cold build is written either
     * way.
     */
    private fun shouldLook(
        account: LibraryAccount,
        key: Key,
        generation: Long,
    ): Boolean {
        val first = synchronized(lock) { diskLooked.add(Triple(account, key.libraries, key.cutoffs)) }
        return first ||
            !snapshots.holdsAll(key.cutoffs.map { SnapshotCache.Key(account, key.libraries, it, generation) })
    }

    /**
     * The days of [disk] from [persisted], or null for none (no key, or a miss of any kind). A build that went stale
     * while the file was read stops at [checkpoint], as any other.
     */
    private suspend fun fromDisk(
        account: LibraryAccount,
        disk: DayKey?,
        scheduled: List<Scheduled>,
        checkpoint: () -> Unit,
    ): List<ChannelDay>? {
        if (persisted == null || disk == null) return null
        val restored = persisted.load(account, disk, scheduled, days::restore)
        if (restored != null) checkpoint()
        return restored
    }

    private fun keep(
        key: Key,
        result: List<ChannelDay>,
    ) = synchronized(lock) {
        kept[key] = result
        while (kept.size > KEPT) kept.remove(kept.keys.first())
    }

    // Written in the source's scope, so a caller is never held up by it and a cancelled caller does not stop it, and on
    // [saveDispatcher], at background priority, so it never competes with a tune for the CPU.
    private fun save(
        account: LibraryAccount,
        disk: DayKey,
        result: List<ChannelDay>,
    ) {
        val to = persisted ?: return
        val job = buildScope.launch(saveDispatcher, start = CoroutineStart.LAZY) { to.save(account, disk, result) }
        synchronized(lock) { saves += job }
        job.invokeOnCompletion { synchronized(lock) { saves -= job } }
        job.start()
    }

    // A disk hit, reported as a build: the whole time is the file's read ([DayBuild.diskMs]), and no snapshot was read
    // and no pool worked out.
    private fun reportDisk(
        scheduled: List<Scheduled>,
        ms: Long,
        channels: Int,
        before: Probed,
        allocBefore: Reading,
    ) {
        val after = Probed(probes)
        val allocated = Reading(probes.allocatedBytes()) - allocBefore
        built(
            DayBuild(
                scheduled.firstOrNull()?.n ?: -1,
                ms,
                0,
                0,
                channels,
                poolsCpuMs = 0,
                snapshotsProcCpuMs = after.cpuMs - before.cpuMs,
                poolsProcCpuMs = 0,
                gcCount = after.gcCount - before.gcCount,
                gcBlockMs = after.gcBlockMs - before.gcBlockMs,
                snapshot = DISK,
                evaluations = 0,
                allocKb = if (allocated < 0) -1 else allocated / BYTES_PER_KB,
                diskMs = ms,
            ),
        )
    }

    /** One snapshot per cut-off of [key], from [snapshots] when the library has not changed since it was read. */
    @Suppress("LongParameterList") // The build's state, passed down.
    private suspend fun snapshotsFor(
        account: LibraryAccount,
        key: Key,
        generation: Long,
        checkpoint: () -> Unit,
        outcomes: MutableList<SnapshotCache.Found>,
    ): Map<Long, RuleSnapshot> {
        val read = HashMap<Long, RuleSnapshot>()
        for (cutoff in key.cutoffs) {
            checkpoint()
            val at = Instant.fromEpochMilliseconds(cutoff)
            val got =
                snapshots.get(SnapshotCache.Key(account, key.libraries, cutoff, generation)) {
                    library.ruleSnapshot(account, key.libraries, at)
                }
            outcomes += got.found
            read[cutoff] = got.snapshot
        }
        return read
    }

    internal companion object {
        /** How many built sets of days are kept: today and tomorrow for a few revisions. */
        const val KEPT = 8

        /**
         * How long before a channel's day ends the day after it is built in the background. A build took up to 26 s on
         * the slowest reference device (#392); an hour leaves room for it however busy the device is, and costs one
         * early build per channel day.
         */
        const val WARM_AHEAD_MS = 60 * 60 * 1000L

        /** `DayBuild.snapshot` for days read from disk ([PersistedDays]). */
        const val DISK = "disk"

        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_KB = 1024L
    }
}

/**
 * Where the time of one build of the days went, in milliseconds (numbers only: no id, name or title). [day] is the
 * first scheduled channel's epoch number, [snapshotsMs] the `ruleSnapshot` reads, [poolsMs] working out the pools
 * and leftovers claims of every channel's day, and [channels] how many days were built. The schedule layout of each
 * day is not part of it: it is timed on its own ([BuiltInScheduleSource]'s `laidOut`).
 *
 * The rest are for telling a slow algorithm from a starved or waiting one, and are all -1 when unknown:
 * - [poolsCpuMs] is the CPU time of the building thread over the pool work, which never suspends and so stays on one
 *   thread: far below [poolsMs] means the thread waited or was descheduled.
 * - [snapshotsProcCpuMs] and [poolsProcCpuMs] are the **process's** CPU time over the snapshot phase and the pool
 *   phase (`Process.getElapsedCpuTime()` deltas). They include every thread of the app, Room's among them and the
 *   garbage collector's, so they are an upper bound on the phase's own CPU, not a measure of it.
 * - [gcCount] and [gcBlockMs] are the collections and the milliseconds the app was blocked by collections across the
 *   whole build, process-wide.
 * - [snapshot] is where the snapshots came from: `hit` (all kept), `miss` (some read or joined a read) or `cleared`
 *   (some were kept but the garbage collector had dropped them), the worst of the cut-offs of the build. `disk` is a
 *   build that read the days themselves from disk ([PersistedDays]): no snapshot was read and no pool worked out, so
 *   [ms] is [diskMs], and [snapshotsMs], [poolsMs], [poolsCpuMs], [poolsProcCpuMs] and [evaluations] are 0.
 * - [evaluations] is how many times `PoolEvaluator.evaluate` ran in the build, and [allocKb] the kilobytes the app
 *   allocated across the build (`art.gc.bytes-allocated`, process-wide), -1 when unknown.
 * - [diskMs] is the time spent looking for the days on disk ([PersistedDays]): the whole build on a `disk` hit, the
 *   failed look on a miss, and -1 when the disk was not looked at.
 */
@Suppress("LongParameterList") // Numbers only, one per fact of the build.
class DayBuild(
    val day: Long,
    val ms: Long,
    val snapshotsMs: Long,
    val poolsMs: Long,
    val channels: Int,
    val poolsCpuMs: Long = -1,
    val snapshotsProcCpuMs: Long = -1,
    val poolsProcCpuMs: Long = -1,
    val gcCount: Long = -1,
    val gcBlockMs: Long = -1,
    val snapshot: String = "",
    val evaluations: Int = -1,
    val allocKb: Long = -1,
    val diskMs: Long = -1,
)

/**
 * The clocks a build reads to report where its time went (numbers only). Each says -1 when it cannot tell. The app
 * gives Android's: `Debug.threadCpuTimeNanos()`, `Process.getElapsedCpuTime()` and `Debug.getRuntimeStat` for
 * `art.gc.gc-count`, `art.gc.blocking-gc-time` and `art.gc.bytes-allocated`. The defaults are the JVM's thread CPU time
 * and nothing else.
 */
class BuildProbes(
    val threadCpuNanos: () -> Long = ThreadCpu::nanos,
    val processCpuMs: () -> Long = { -1L },
    val gcCount: () -> Long = { -1L },
    val gcBlockMs: () -> Long = { -1L },
    val allocatedBytes: () -> Long = { -1L },
)

private fun threadCpuMs(
    before: Long,
    after: Long,
): Long = if (before < 0 || after < before) -1 else (after - before) / BuiltInScheduleSource.NANOS_PER_MS

/** The process-wide readings at one moment; the difference of two is -1 if either was unknown. */
private class Probed(
    probes: BuildProbes,
) {
    val cpuMs = Reading(probes.processCpuMs())
    val gcCount = Reading(probes.gcCount())
    val gcBlockMs = Reading(probes.gcBlockMs())
}

@JvmInline
private value class Reading(
    val value: Long,
) {
    operator fun minus(earlier: Reading): Long = if (value < 0 || earlier.value < 0) -1 else value - earlier.value
}

/** The JVM's CPU time of the calling thread, or -1 where that is not available (the app passes Android's own). */
internal object ThreadCpu {
    fun nanos(): Long =
        try {
            val bean =
                java.lang.management.ManagementFactory
                    .getThreadMXBean()
            if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime else -1L
        } catch (_: Exception) {
            -1L
        } catch (_: LinkageError) {
            -1L
        }
}

private fun ChannelDay.airing() =
    ChannelAiring(
        id = channel.id,
        number = checkNotNull(channel.number),
        name = name,
        epochStart = Instant.fromEpochMilliseconds(epochStartMs),
        hidden = hidden,
        ceilingRank = ceilingRank,
    )

private fun ScheduleBlock.toAiring(info: Map<CanonicalId, ProgramInfo>): AiringBlock =
    when (val c = content) {
        is ScheduleContent.Program -> {
            AiringBlock(startMs, endMs, Airing.Program(c.itemId, c.mediaStartOffsetMs, info[c.itemId]))
        }

        ScheduleContent.Slate -> {
            AiringBlock(startMs, endMs, Airing.Slate)
        }
    }

/** One `ruleSnapshot` per cut-off in [cutoffs], by cut-off. */
internal suspend fun snapshotsOf(
    library: LibraryRepository,
    account: LibraryAccount,
    libraries: Set<ItemId>,
    cutoffs: Collection<Long>,
): Map<Long, RuleSnapshot> {
    val read = HashMap<Long, RuleSnapshot>()
    for (cutoff in cutoffs) {
        read[cutoff] = library.ruleSnapshot(account, libraries, Instant.fromEpochMilliseconds(cutoff))
    }
    return read
}

/**
 * Where the built days are written to disk: one thread at the lowest priority, so that encoding a day (a second or so
 * on the slowest TVs) runs only when nothing a viewer waits for wants the CPU. Made on first use, and a daemon.
 */
internal object BackgroundSaves {
    val dispatcher: CoroutineDispatcher by lazy {
        Executors
            .newSingleThreadExecutor { task ->
                Thread(task, "day-saves").apply {
                    priority = Thread.MIN_PRIORITY
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
    }
}
