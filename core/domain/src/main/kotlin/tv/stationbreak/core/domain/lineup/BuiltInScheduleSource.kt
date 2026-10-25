// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
 * one. The built days (settings, epoch and day pool) are kept per lineup revision and cut-offs, so asking again
 * within the same days reads nothing. [onAir] builds a channel's day once (kept on the cached `ChannelDay`) and reads
 * the names of the blocks it returns with one `programInfo` call; the days themselves stay internal to this class.
 *
 * Callers that miss the cache together share one build (single flight, #392): the first starts it in this source's own
 * scope and the rest await it, so a caller that is cancelled (a flip superseded mid-lookup) leaves the build running
 * for the others, and for the cache. A build that fails is not kept; the next caller builds again.
 *
 * [timed] is told the build of each following day that [onAir] needed: the day's index and the milliseconds it took
 * (numbers only; no item, title or URL ever reaches it), so a first tune near a day edge can be measured. [built] is
 * told every build as it completes, with where its time went ([DayBuild]; numbers only), and [laidOut] each channel
 * day's schedule layout (worked out once, on first use; [warm] does it ahead).
 *
 * Main-safe: [airings], [onAir] and [standIns] do their work on [dispatcher] (default: `Dispatchers.Default`), so
 * building a day never runs on, or blocks, the caller's thread.
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
    private val libraries: suspend () -> Set<ItemId>?,
) : ScheduleSource {
    private data class Key(
        val account: LibraryAccount,
        val revision: Long,
        val libraries: Set<ItemId>,
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
        val held = service.loaded(account) ?: return emptyList<ChannelDay>() to null
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds())
        val key = Key(account, held.revision, libraries, scheduled.map { it.cutoffMs }.distinct().sorted())
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
                                    build(key, account, held.lineup, scheduled, self)
                                }
                            self
                        }
                }
            }
        // Started outside the lock, once the entry it removes itself from is in place.
        if (started) flight?.start()
        if (!started && flight != null) joined.incrementAndGet()
        val days = hit ?: awaited(checkNotNull(flight))
        return days to if (started) began.elapsedNow().inWholeMilliseconds else null
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

    private suspend fun build(
        key: Key,
        account: LibraryAccount,
        lineup: Lineup,
        scheduled: List<Scheduled>,
        self: Deferred<List<ChannelDay>>,
    ): List<ChannelDay> {
        try {
            val began = TimeSource.Monotonic.markNow()
            val snapshots = snapshotsOf(library, account, key.libraries, key.cutoffs)
            val snapshotsMs = began.elapsedNow().inWholeMilliseconds
            val result = days.days(lineup, scheduled, snapshots)
            val totalMs = began.elapsedNow().inWholeMilliseconds
            synchronized(lock) {
                kept[key] = result
                while (kept.size > KEPT) kept.remove(kept.keys.first())
            }
            // Pools and leftovers claims; the schedule layout of each day is worked out on first use (see [laidOut]).
            built(DayBuild(scheduled.firstOrNull()?.n ?: -1, totalMs, snapshotsMs, totalMs - snapshotsMs, result.size))
            return result
        } finally {
            // Only this build's own entry: a failed or cancelled build leaves nothing kept, so the next caller builds.
            synchronized(lock) { inFlight.remove(key, self) }
        }
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
    }
}

/**
 * Where the time of one build of the days went, in milliseconds (numbers only: no id, name or title). [day] is the
 * first scheduled channel's epoch number, [snapshotsMs] the `ruleSnapshot` reads, [poolsMs] working out the pools
 * and leftovers claims of every channel's day, and [channels] how many days were built. The schedule layout of each
 * day is not part of it: it is timed on its own ([BuiltInScheduleSource]'s `laidOut`).
 */
class DayBuild(
    val day: Long,
    val ms: Long,
    val snapshotsMs: Long,
    val poolsMs: Long,
    val channels: Int,
)

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
