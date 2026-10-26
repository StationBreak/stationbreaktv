// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.HOUR
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.time.Instant

/** The shared helpers of tests that script a schedule. Synthetic only: made-up ids and titles. */
object ScheduleFixtures {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN

    /** The start of the test day; every block time is a number of minutes from it. */
    const val T0 = 1_700_000_000_000L - 1_700_000_000_000L % (24 * HOUR)

    val CH7 = ChannelId("ch-7")
    val CH5 = ChannelId("ch-5")
    val CH9 = ChannelId("ch-9")

    fun id(n: Int): CanonicalId = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    fun itemOf(n: Int): ItemId = ItemId(id(n).value)

    fun info(
        n: Int,
        title: String = "Test Movie $n",
    ) = ProgramInfo(id(n), ScheduleItemKind.Movie, title, null, null, null)

    fun episodeInfo(
        n: Int,
        series: String,
        season: Int,
        episode: Int,
    ) = ProgramInfo(id(n), ScheduleItemKind.Episode, "Test Episode $episode", series, season, episode)

    /** A program block from minute [from] to [to] (minutes from the test day's start). */
    fun program(
        from: Long,
        to: Long,
        n: Int,
        mediaStartMs: Long = 0,
        info: ProgramInfo? = info(n),
    ) = AiringBlock(T0 + from * MIN, T0 + to * MIN, Airing.Program(id(n), mediaStartMs, info))

    fun slate(
        from: Long,
        to: Long,
    ) = AiringBlock(T0 + from * MIN, T0 + to * MIN, Airing.Slate)

    fun standIn(
        from: Long,
        to: Long,
        n: Int,
        forBlockStartMin: Long,
    ) = AiringBlock(T0 + from * MIN, T0 + to * MIN, Airing.StandIn(id(n), info(n), T0 + forBlockStartMin * MIN))
}

/** A schedule the test edits while it runs. Day length 24 h from [ScheduleFixtures.T0]. */
class FakeSchedule : ScheduleSource {
    class Entry(
        val number: Int,
        val name: String,
        var hidden: HiddenReason? = null,
        var blocks: List<AiringBlock> = emptyList(),
        /** The channel's day length; the schedule is built to the end of the day after the one that holds `at`. */
        var epochMs: Long = DAY,
        /** The tier of the channel's rating ceiling, null for none. */
        var ceilingRank: Int? = null,
    )

    val channels = linkedMapOf<ChannelId, Entry>()
    var standInScript: (AiringBlock, Set<CanonicalId>) -> List<AiringBlock> = { _, _ -> emptyList() }
    val standInCalls = mutableListOf<Pair<AiringBlock, Set<CanonicalId>>>()
    val onAirCalls = mutableListOf<Pair<ChannelId, Long>>()

    /** The windows asked for through [schedule], in order. */
    val scheduleCalls = mutableListOf<Triple<Instant, Instant, ScheduleBuild>>()

    /** Told how many following blocks each [onAir] asks for, as it is asked; may throw to fail the call. */
    var onAirObserver: (next: Int) -> Unit = {}

    /** How long each [onAir] takes on the virtual clock (a slow schedule read). */
    var onAirDelayMs = 0L

    /** The numbers (counting from 1) of the [onAir] calls that throw once their wait is over. */
    var onAirThrowsAfterWaitOn: Set<Int> = emptySet()

    /** When set, the [onAirDelayMs] wait ignores cancellation (a read that unwinds slowly). */
    var onAirUncancellable = false

    /** For [ScheduleBuild.CachedOnly]: blocks starting at or after this are not cached (null: everything is). */
    var cachedUntilMs: Long? = null

    /** For [ScheduleBuild.CachedOnly]: nothing is cached at all. */
    var nothingCached = false

    /** Nobody is signed in: [onAir] and [schedule] give null, and [airings] nothing. */
    var signedOut = false

    fun channel(
        id: ChannelId,
        number: Int,
        blocks: List<AiringBlock>,
        hidden: HiddenReason? = null,
    ): Entry = Entry(number, "Test Channel $number", hidden, blocks).also { channels[id] = it }

    private fun airing(
        id: ChannelId,
        e: Entry,
        at: Long,
    ) = ChannelAiring(id, e.number, e.name, Instant.fromEpochMilliseconds(dayStart(e, at)), e.hidden, e.ceilingRank)

    private fun dayStart(
        e: Entry,
        at: Long,
    ) = T0 + (at - T0).floorDiv(e.epochMs) * e.epochMs

    override suspend fun onAir(
        channel: ChannelId,
        at: Instant,
        next: Int,
    ): ChannelOnAir? {
        val ms = at.toEpochMilliseconds()
        onAirCalls += channel to ms
        onAirObserver(next)
        if (signedOut) return null
        if (onAirUncancellable) {
            withContext(NonCancellable) { delay(onAirDelayMs) }
        } else {
            delay(onAirDelayMs)
        }
        check(onAirCalls.size !in onAirThrowsAfterWaitOn) { "lookup failed" }
        val e = channels[channel]?.takeIf { it.hidden == null }
        val index = e?.blocks?.indexOfFirst { ms >= it.startMs && ms < it.endMs } ?: -1
        return e?.takeIf { index >= 0 }?.let {
            val builtUntil = dayStart(it, ms) + 2 * it.epochMs
            ChannelOnAir(airing(channel, it, ms), it.blocks[index], it.blocks.drop(index + 1).take(next), builtUntil)
        }
    }

    override suspend fun standIns(
        channel: ChannelId,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock> {
        standInCalls += block to exclude
        return standInScript(block, exclude)
    }

    /** How long each [airings] takes on the virtual clock (a slow lineup read). */
    var airingsDelayMs = 0L

    /** The numbers of the lineup reads (counting from 1) that fail. */
    var airingsFailOn: Set<Int> = emptySet()

    /** How many lineup reads were made. */
    var airingsCalls = 0

    override suspend fun airings(at: Instant): List<ChannelAiring> {
        airingsCalls++
        if (signedOut) return emptyList()
        check(airingsCalls !in airingsFailOn) { "lineup read failed" }
        delay(airingsDelayMs)
        return channels.entries.sortedBy { it.value.number }.map { (id, e) -> airing(id, e, at.toEpochMilliseconds()) }
    }

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        require(until > from) { "The window must end after it starts" }
        scheduleCalls += Triple(from, until, build)
        if (signedOut) return null
        if (build == ScheduleBuild.CachedOnly && nothingCached) return ScheduleWindow(emptyList(), complete = false)
        val fromMs = from.toEpochMilliseconds()
        val untilMs = until.toEpochMilliseconds()
        val cachedUntil = cachedUntilMs.takeIf { build == ScheduleBuild.CachedOnly }
        // Nothing of the day that holds the start is cached: the same as nothing cached at all.
        if (cachedUntil != null && channels.values.any { cachedUntil <= dayStart(it, fromMs) }) {
            return ScheduleWindow(emptyList(), complete = false)
        }
        val rows =
            channels.entries
                .filter { it.value.hidden == null }
                .sortedBy { it.value.number }
                .map { (id, e) ->
                    val builtUntil = dayStart(e, fromMs) + 2 * e.epochMs
                    val blocks =
                        e.blocks.filter {
                            it.endMs > fromMs && it.startMs < untilMs && it.startMs < builtUntil &&
                                (cachedUntil == null || it.startMs < cachedUntil)
                        }
                    ChannelRow(airing(id, e, fromMs), blocks, builtUntil)
                }
        val complete = rows.none { (it.blocks.lastOrNull()?.endMs ?: fromMs) < minOf(untilMs, it.builtUntilMs) }
        return ScheduleWindow(rows, complete)
    }

    companion object {
        const val DAY = 24 * HOUR
    }
}
