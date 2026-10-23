// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryLastChannelStore
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

const val MIN = 60_000L
const val HOUR = 60 * MIN

/** The start of the test day; every block time is a number of minutes from it. */
const val T0 = 1_700_000_000_000L - 1_700_000_000_000L % (24 * HOUR)

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

val CH7 = ChannelId("ch-7")
val CH5 = ChannelId("ch-5")
val CH9 = ChannelId("ch-9")

/** A schedule the test edits while it runs. Day length 24 h from [T0]. */
class FakeSchedule : ScheduleSource {
    class Entry(
        val number: Int,
        val name: String,
        var hidden: HiddenReason? = null,
        var blocks: List<AiringBlock> = emptyList(),
        /** The channel's day length; the schedule is built to the end of the day after the one that holds `at`. */
        var epochMs: Long = DAY,
    )

    val channels = linkedMapOf<ChannelId, Entry>()
    var standInScript: (AiringBlock, Set<CanonicalId>) -> List<AiringBlock> = { _, _ -> emptyList() }
    val standInCalls = mutableListOf<Pair<AiringBlock, Set<CanonicalId>>>()
    val onAirCalls = mutableListOf<Pair<ChannelId, Long>>()

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
    ) = ChannelAiring(id, e.number, e.name, Instant.fromEpochMilliseconds(dayStart(e, at)), e.hidden)

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

    override suspend fun airings(at: Instant): List<ChannelAiring> =
        channels.entries.sortedBy { it.value.number }.map { (id, e) -> airing(id, e, at.toEpochMilliseconds()) }

    companion object {
        const val DAY = 24 * HOUR
    }
}

/** One thing the machine asked the port to do, and when (milliseconds since the test began). */
sealed interface Call {
    val at: Long

    data class Tune(
        val item: ItemId,
        val start: Duration,
        override val at: Long,
    ) : Call

    data class PreResolve(
        val item: ItemId,
        val start: Duration,
        override val at: Long,
    ) : Call

    data class TuneHandle(
        val item: ItemId,
        val start: Duration,
        override val at: Long,
    ) : Call

    data class Discard(
        val item: ItemId,
        override val at: Long,
    ) : Call

    data class Stop(
        override val at: Long,
    ) : Call

    data class Close(
        override val at: Long,
    ) : Call
}

private class Prepared(
    val item: ItemId,
    val start: Duration,
)

/** A [TunePort] that records its calls and answers from a script per item (default: plays, never ends). */
class FakeTune(
    private val now: () -> Long,
) : TunePort {
    val calls = mutableListOf<Call>()
    val scripts = mutableMapOf<ItemId, ArrayDeque<suspend () -> TuneOutcome>>()
    var preResolveResult: (ItemId) -> Boolean = { true }

    fun script(
        item: ItemId,
        vararg steps: suspend () -> TuneOutcome,
    ) {
        scripts.getOrPut(item) { ArrayDeque() }.addAll(steps)
    }

    /** A play that ends [after] from the moment the tune is answered (not from when someone starts collecting). */
    fun playsThenEnds(
        after: Duration,
        end: PlaybackEnd,
    ): suspend () -> TuneOutcome =
        {
            val due = now() + after.inWholeMilliseconds
            plays(
                flow {
                    delay(due - now())
                    emit(end)
                },
            )
        }

    private suspend fun outcomeFor(item: ItemId): TuneOutcome = scripts[item]?.removeFirstOrNull()?.invoke() ?: plays()

    override suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
    ): TuneOutcome {
        calls += Call.Tune(item, start, now())
        return outcomeFor(item)
    }

    override suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): NextHandle? {
        calls += Call.PreResolve(item, start, now())
        return if (preResolveResult(item)) NextHandle(Prepared(item, start)) else null
    }

    override suspend fun tune(
        session: Session,
        next: NextHandle,
    ): TuneOutcome {
        val p = next.token as Prepared
        calls += Call.TuneHandle(p.item, p.start, now())
        return outcomeFor(p.item)
    }

    override suspend fun discard(
        session: Session,
        next: NextHandle,
    ) {
        calls += Call.Discard((next.token as Prepared).item, now())
    }

    override suspend fun stop(session: Session) {
        calls += Call.Stop(now())
    }

    override suspend fun close(session: Session) {
        calls += Call.Close(now())
    }

    companion object {
        fun plays(end: Flow<PlaybackEnd> = flow { awaitCancellation() }): TuneOutcome = TuneOutcome.Playing(end)

        fun dead(timedOut: Boolean = false): suspend () -> TuneOutcome = { TuneOutcome.Dead(timedOut) }

        fun deadAfter(
            wait: Duration,
            timedOut: Boolean = false,
        ): suspend () -> TuneOutcome =
            {
                delay(wait)
                TuneOutcome.Dead(timedOut)
            }

        fun playing(): suspend () -> TuneOutcome = { plays() }
    }
}

class CountingLastChannel(
    private val inner: InMemoryLastChannelStore = InMemoryLastChannelStore(),
) : LastChannelStore {
    var saves = 0

    override suspend fun load(account: LibraryAccount): ChannelId? = inner.load(account)

    override suspend fun save(
        account: LibraryAccount,
        channel: ChannelId,
    ): Boolean {
        saves++
        return inner.save(account, channel)
    }
}

class FakeChannels : ChannelRepository {
    val flow = MutableStateFlow<List<ChannelSummary>>(emptyList())

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flow

    fun publish(vararg ids: Pair<ChannelId, Int>) {
        flow.value =
            ids.map { (id, number) ->
                val since = Instant.fromEpochMilliseconds(T0)
                ChannelSummary(id, number, "Test Channel $number", ChannelStatus.On, ChannelKind.Starter, since)
            }
    }
}

/** Everything a live-channel test needs, on the test scope's virtual time. The clock starts [startMin] into the day. */
class Rig(
    val scope: TestScope,
    startMin: Long = 10,
    timings: LiveTimings = LiveTimings(),
) {
    private val startMs = T0 + startMin * MIN

    /** Added to the clock by a test that wants the clock to jump. */
    var jumpMs = 0L

    fun nowMs(): Long = startMs + scope.currentTime + jumpMs

    val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs())
        }
    val schedule = FakeSchedule()
    val tune = FakeTune { scope.currentTime }
    val lastChannel = CountingLastChannel()
    val channels = FakeChannels()
    val library = InMemoryLibraryRepository()
    val account = LibraryAccount(ServerId("srv1"), UserId("u"))
    val session =
        Session(
            UserId("u"),
            AccessToken("fake-token"),
            ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "jellyfin.example", 443),
            null,
            false,
        )
    val sessions = SessionRepository(InMemorySecretStore(), FakeMediaServer())

    val live = LiveChannel(schedule, tune, lastChannel, sessions, library, channels, clock, timings)

    /** Every state in order, each with how many port calls had been made when it appeared. */
    val states = mutableListOf<Pair<LiveState, Int>>()

    val seen: List<LiveState> get() = states.map { it.first }

    suspend fun start(): Job {
        sessions.save(session)
        library.useAccount(account)
        channels.publish(*schedule.channels.map { it.key to it.value.number }.toTypedArray())
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            live.state.collect { states += it to tune.calls.size }
        }
        val job = scope.backgroundScope.launch { live.run() }
        scope.runCurrent()
        return job
    }

    /** Starts another sitting on the same machine (the setup is already done). */
    fun launchRun(): Job {
        val job = scope.backgroundScope.launch { live.run() }
        scope.runCurrent()
        return job
    }

    /** Moves virtual time to [ms] after the test began (and runs what is due then). */
    fun at(ms: Long) {
        scope.advanceTimeBy(ms - scope.currentTime)
        scope.runCurrent()
    }

    fun atMin(min: Long) = at(min * MIN)

    val state: LiveState get() = live.state.value

    val tunes: List<Call.Tune> get() = tune.calls.filterIsInstance<Call.Tune>()
}

/** JUnit 4 has no assertIs: this fails the test with the actual value when it is not a [T]. */
inline fun <reified T> assertIs(value: Any?): T {
    check(value is T) { "Expected ${T::class.simpleName} but was $value" }
    return value
}
