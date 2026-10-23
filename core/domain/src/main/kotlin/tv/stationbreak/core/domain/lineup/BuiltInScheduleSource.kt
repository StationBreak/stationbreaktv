// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
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
 * [timed] is told the build of each following day that [onAir] needed: the day's index and the milliseconds it took
 * (numbers only; no item, title or URL ever reaches it), so a first tune near a day edge can be measured.
 */
class BuiltInScheduleSource(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val timed: (dayIndex: Long, buildMs: Long) -> Unit = { _, _ -> },
    private val libraries: suspend () -> Set<ItemId>?,
) : ScheduleSource {
    private data class Key(
        val revision: Long,
        val libraries: Set<ItemId>,
        val cutoffs: List<Long>,
    )

    private val days = LineupDays(service.catalog)
    private val lock = Any()
    private val built = LinkedHashMap<Key, List<ChannelDay>>()

    override suspend fun airings(at: Instant): List<ChannelAiring> {
        val (account, chosen) = signedIn() ?: return emptyList()
        return channelDays(account, chosen, at).map { it.airing() }
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
        val (account, chosen) = signedIn() ?: return null
        val today = channelDays(account, chosen, at).firstOrNull { it.channel.id == channel && it.hidden == null }
        return today?.let { onAirOf(account, chosen, it, at, next) }
    }

    private suspend fun onAirOf(
        account: LibraryAccount,
        chosen: Set<ItemId>,
        today: ChannelDay,
        at: Instant,
        next: Int,
    ): ChannelOnAir {
        val now = today.day.whatsOn(at.toEpochMilliseconds(), next)
        val following = followingBlocks(account, chosen, today, next - now.next.size)
        val blocks = listOf(now.block) + now.next + following
        val ids = blocks.mapNotNullTo(HashSet()) { (it.content as? ScheduleContent.Program)?.itemId }
        val info = if (ids.isEmpty()) emptyMap() else library.programInfo(account, ids)
        val airings = blocks.map { it.toAiring(info) }
        return ChannelOnAir(today.airing(), airings.first(), airings.drop(1))
    }

    /**
     * [count] blocks after [from]'s day, from the days that follow it, each built from its own snapshot. Fewer only if
     * the channel stops airing.
     */
    private suspend fun followingBlocks(
        account: LibraryAccount,
        chosen: Set<ItemId>,
        from: ChannelDay,
        count: Int,
    ): List<ScheduleBlock> {
        val blocks = ArrayList<ScheduleBlock>()
        var day = from
        while (blocks.size < count) {
            val began = TimeSource.Monotonic.markNow()
            val start = Instant.fromEpochMilliseconds(day.day.endMs)
            day = channelDays(account, chosen, start).firstOrNull { it.channel.id == from.channel.id } ?: break
            val built = day.day
            timed(day.n, began.elapsedNow().inWholeMilliseconds)
            val ahead = built.whatsOn(built.startMs, count - blocks.size - 1)
            blocks += ahead.block
            blocks += ahead.next
        }
        return blocks
    }

    /** The switched-on channels' days at [at], in number order. */
    internal suspend fun channelDays(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): List<ChannelDay> {
        val held = service.loaded(account) ?: return emptyList()
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds())
        val key = Key(held.revision, libraries, scheduled.map { it.cutoffMs }.distinct().sorted())
        return synchronized(lock) { built[key] } ?: build(key, account, held.lineup, scheduled)
    }

    private suspend fun build(
        key: Key,
        account: LibraryAccount,
        lineup: Lineup,
        scheduled: List<Scheduled>,
    ): List<ChannelDay> {
        val snapshots = snapshotsOf(library, account, key.libraries, key.cutoffs)
        val result = days.days(lineup, scheduled, snapshots)
        synchronized(lock) {
            built[key] = result
            while (built.size > KEPT) built.remove(built.keys.first())
        }
        return result
    }

    private companion object {
        /** How many built sets of days are kept: today and tomorrow for a few revisions. */
        const val KEPT = 8
    }
}

private fun ChannelDay.airing() =
    ChannelAiring(
        id = channel.id,
        number = checkNotNull(channel.number),
        name = name,
        epochStart = Instant.fromEpochMilliseconds(epochStartMs),
        hidden = hidden,
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
