// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.PlayerEvent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

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

    /** How long each [stop] takes on the virtual clock (a slow player). */
    var stopDelayMs = 0L
    var preResolveResult: (ItemId) -> Boolean = { true }

    /** What every play tells the reporter about its route (null: nothing, so nothing is reported). */
    var reportFacts: ReportFacts? = null

    /** Where a [FakePlayControl] runs its delayed seek ends; the rig sets it to the test's background scope. */
    var controlScope: CoroutineScope? = null

    /** Each play's transport handle, with the item it plays, in tune order (a script's own control is not here). */
    val controls = mutableListOf<Pair<ItemId, FakePlayControl>>()

    /** The transport handle of the last play the port answered. */
    val lastControl: FakePlayControl? get() = controls.lastOrNull()?.second

    /** The transport handle of the last play of [item]. */
    fun controlOf(item: ItemId): FakePlayControl? = controls.lastOrNull { it.first == item }?.second

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

    /** The scripted outcome; a play gets the rig's report facts and, unless it has its own, a [FakePlayControl]. */
    private suspend fun outcomeFor(
        item: ItemId,
        start: Duration,
    ): TuneOutcome {
        val outcome = scripts[item]?.removeFirstOrNull()?.invoke() ?: plays()
        if (outcome !is TuneOutcome.Playing) return outcome
        val control =
            if (outcome.control == PlayControl.None) {
                FakePlayControl(now, start.inWholeMilliseconds, controlScope).also { controls += item to it }
            } else {
                outcome.control
            }
        return outcome.copy(control = control, report = outcome.report ?: reportFacts)
    }

    override suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
    ): TuneOutcome {
        calls += Call.Tune(item, start, now())
        return outcomeFor(item, start)
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
        start: Duration?,
    ): TuneOutcome {
        val p = next.token as Prepared
        calls += Call.TuneHandle(p.item, start ?: p.start, now())
        return outcomeFor(p.item, start ?: p.start)
    }

    override suspend fun discard(
        session: Session,
        next: NextHandle,
    ) {
        calls += Call.Discard((next.token as Prepared).item, now())
    }

    override suspend fun stop(session: Session) {
        calls += Call.Stop(now())
        delay(stopDelayMs)
    }

    override suspend fun close(session: Session) {
        calls += Call.Close(now())
    }

    companion object {
        fun plays(
            end: Flow<PlaybackEnd> = flow { awaitCancellation() },
            timings: TuneTimings = TuneTimings(),
            control: PlayControl = PlayControl.None,
        ): TuneOutcome = TuneOutcome.Playing(end, control, timings)

        /** A tune that takes [wait] to show a picture, with the [timings] the controller would have measured. */
        fun playsAfter(
            wait: Duration,
            timings: TuneTimings = TuneTimings(firstFrameMs = wait.inWholeMilliseconds),
        ): suspend () -> TuneOutcome =
            {
                delay(wait)
                plays(timings = timings)
            }

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

/**
 * A [PlayControl] on the virtual clock [now]: the position starts at [startMs] and moves with the clock (at [rate])
 * while playing. It records its calls (`pause`, `resume`, `seek:<ms>`) and sends the events a player would.
 */
class FakePlayControl(
    private val now: () -> Long,
    startMs: Long,
    private val scope: CoroutineScope? = null,
) : PlayControl {
    override val generation: Long = 1L
    val calls = mutableListOf<String>()

    /** When true, [position] answers null (the play is no longer on the player) and events carry no position. */
    var positionUnknown = false
    var seekableValue = true

    /** How long a seek takes to be done; 0 sends `SeekDone` at once. A delay needs the control's scope. */
    var seekDelayMs = 0L

    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)

    private var baseMs = startMs
    private var sinceMs = now()
    private var playing = true

    /** How fast the media runs against the clock while playing (1.0: real time). */
    var rate: Double = 1.0
        set(value) {
            rebase()
            field = value
        }

    /** The true media position now. */
    fun positionMs(): Long = if (playing) baseMs + ((now() - sinceMs) * rate).toLong() else baseMs

    private fun rebase() {
        baseMs = positionMs()
        sinceMs = now()
    }

    private fun known(ms: Long) = if (positionUnknown) PlayerEvent.NOT_KNOWN else ms

    override fun pause() {
        if (!playing) return
        calls += "pause"
        rebase()
        playing = false
        events.tryEmit(PlayerEvent.Paused(generation, now(), known(baseMs)))
    }

    override fun resume() {
        if (playing) return
        calls += "resume"
        rebase()
        playing = true
        events.tryEmit(PlayerEvent.Resumed(generation, now()))
        events.tryEmit(PlayerEvent.Playing(now(), baseMs.milliseconds, generation))
    }

    override fun seekTo(position: Duration) {
        val from = positionMs()
        val to = position.inWholeMilliseconds
        calls += "seek:$to"
        baseMs = to
        sinceMs = now()
        events.tryEmit(PlayerEvent.Seeking(generation, now(), from, to))
        val delayed = scope
        if (seekDelayMs <= 0 || delayed == null) {
            events.tryEmit(PlayerEvent.SeekDone(generation, now(), known(to)))
        } else {
            delayed.launch {
                delay(seekDelayMs)
                events.emit(PlayerEvent.SeekDone(generation, now(), known(to)))
            }
        }
    }

    override suspend fun position(): Duration? = if (positionUnknown) null else positionMs().milliseconds

    override fun seekable(): Boolean = seekableValue
}

class CountingLastChannel(
    private val inner: InMemoryLastChannelStore = InMemoryLastChannelStore(),
) : LastChannelStore {
    var saves = 0

    /** How long each save takes on the virtual clock. */
    var saveDelayMs = 0L

    override suspend fun load(account: LibraryAccount): ChannelId? = inner.load(account)

    override suspend fun save(
        account: LibraryAccount,
        channel: ChannelId,
    ): Boolean {
        kotlinx.coroutines.delay(saveDelayMs)
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
    server: MediaServer? = null,
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
    val tune = FakeTune { scope.currentTime }.also { it.controlScope = scope.backgroundScope }
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
    val media = FakeMediaServer()
    val sessions = SessionRepository(InMemorySecretStore(), media)

    /** The details the info overlay fetches: asks [media], which a test scripts with `itemDetailsResult`. */
    val details = ProgramDetailsCache(media, sessions, scope.backgroundScope)

    /** Every report the machine made, in order. */
    val reports = mutableListOf<TuneReport>()

    val live =
        LiveChannel(
            schedule,
            tune,
            lastChannel,
            sessions,
            library,
            channels,
            clock,
            timings,
            { reports += it },
            server,
            details = details,
        )

    /** Every state in order, each with how many port calls had been made when it appeared. */
    val states = mutableListOf<Pair<LiveState, Int>>()

    val seen: List<LiveState> get() = states.map { it.first }

    suspend fun start(
        withAccount: Boolean = true,
        commands: Flow<LiveCommand> = emptyFlow(),
    ): Job {
        sessions.save(session)
        if (withAccount) library.useAccount(account)
        channels.publish(*schedule.channels.map { it.key to it.value.number }.toTypedArray())
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            live.state.collect { states += it to tune.calls.size }
        }
        val job = scope.backgroundScope.launch { live.run(commands) }
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
