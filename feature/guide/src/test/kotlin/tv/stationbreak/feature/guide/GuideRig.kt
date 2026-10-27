// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant

/** A schedule that lets a test change the lineup as the guide asks for it. */
internal class HookedSchedule(
    val inner: FakeSchedule,
) : ScheduleSource by inner {
    var calls = 0
    var onSchedule: (call: Int) -> Unit = {}

    /** Runs after the call has its answer, before it returns it. */
    var afterSchedule: (call: Int) -> Unit = {}

    /** When set, an `AsNeeded` read waits for it (a slow build). */
    var asNeededGate: CompletableDeferred<Unit>? = null

    /** When set, a `CachedOnly` read waits for it (a slow read of what is built). */
    var cachedGate: CompletableDeferred<Unit>? = null

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        calls++
        onSchedule(calls)
        if (build == ScheduleBuild.AsNeeded) asNeededGate?.await()
        if (build == ScheduleBuild.CachedOnly) cachedGate?.await()
        return inner.schedule(from, until, build).also { afterSchedule(calls) }
    }
}

/** What the guide's ViewModel tests share: a lineup, a clock the test moves, and a guide on them. */
@OptIn(SecretAccess::class)
open class GuideRig {
    internal val fake = FakeSchedule()
    internal val schedule = HookedSchedule(fake)
    internal val server = FakeMediaServer()
    internal val sessions = SessionRepository(InMemorySecretStore(), server)

    // Channel 7 airs half-hour programs, channel 5 hour-long ones, channel 9 a long program, a slate, then more.
    internal fun lineup(
        untilMin: Long = 600,
        stopCh7AtMin: Long = untilMin,
    ) {
        fake.channels.clear()
        fake.channel(CH7, 7, halfHours(stopCh7AtMin))
        fake.channel(CH5, 5, (0 until untilMin step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(
            CH9,
            9,
            (
                listOf(program(0, 150, 300), slate(150, 210)) +
                    (210 until untilMin step 60).map { program(it, it + 60, 301 + (it / 60).toInt()) }
            ).filter { it.startMs < T0 + untilMin * MIN },
        )
    }

    internal fun halfHours(
        untilMin: Long,
        fromMin: Long = 0,
    ): List<AiringBlock> = (fromMin until untilMin step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) }

    /** Channel 7 with no cell starting at 90: one runs 0 to 95 and the next 95 to 120. */
    internal fun splitAround90(untilMin: Long = 600): List<AiringBlock> =
        listOf(program(0, 95, 900), program(95, 120, 901)) + halfHours(untilMin, fromMin = 120)

    /** How far behind the scheduler the clock's next [lagReads] reads are; the reads after them are on time again. */
    internal var lagNextReadMs = 0L

    /** How many reads [lagNextReadMs] holds for. */
    internal var lagReads = 1

    internal fun TestScope.clock(startMin: Long) =
        object : Clock {
            override fun now(): Instant {
                val lag = lagNextReadMs
                if (lag != 0L && --lagReads <= 0) {
                    lagNextReadMs = 0L
                    lagReads = 1
                }
                return Instant.fromEpochMilliseconds(T0 + startMin * MIN + testScheduler.currentTime - lag)
            }
        }

    internal suspend fun TestScope.newGuide(startMin: Long): GuideViewModel {
        val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
        val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
        sessions.save(Session(UserId("u1"), AccessToken("fake-token-0123"), info, address, null, true))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob() + dispatcher))
        return GuideViewModel(schedule, cache, clock(startMin), dispatcher)
    }

    /** Runs [body] with a guide whose clock reads [startMin] minutes into the test day, and stops it after. */
    internal fun guideTest(
        startMin: Long = 65,
        body: suspend TestScope.(GuideViewModel) -> Unit,
    ) = runTest {
        lineup()
        val vm = newGuide(startMin)
        try {
            body(vm)
        } finally {
            vm.close()
        }
    }

    internal fun TestScope.after(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    internal fun min(m: Long) = T0 + m * MIN

    internal fun GuideViewModel.ready() = snapshot.value.state as GuideState.Ready

    internal fun GuideViewModel.focused() = checkNotNull(snapshot.value.focus)

    // The two halves of the snapshot, each as it changes.
    internal fun GuideViewModel.states() = snapshot.map { it.state }.distinctUntilChanged()

    internal fun GuideViewModel.focuses() = snapshot.map { it.focus }.distinctUntilChanged()
}
