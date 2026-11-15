// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a channel switched on mid-day goes through before the viewer is told it is ready (#776). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelReadinessTest {
    private val neon = LineupIds.starter("neon")
    private val noir = LineupIds.starter("noir")
    private val calls = mutableListOf<Instant>()
    private var answer: () -> List<ChannelAiring> = { emptyList() }
    private var active: LibraryAccount? = ACCOUNT

    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private val source =
        object : ScheduleSource {
            override suspend fun onAir(
                channel: ChannelId,
                at: Instant,
                next: Int,
            ): ChannelOnAir? = error("not used")

            override suspend fun standIns(
                channel: ChannelId,
                block: AiringBlock,
                exclude: Set<CanonicalId>,
            ): List<AiringBlock> = error("not used")

            override suspend fun airings(at: Instant): List<ChannelAiring> {
                calls += at
                return answer()
            }

            override suspend fun schedule(
                from: Instant,
                until: Instant,
                build: ScheduleBuild,
            ): ScheduleWindow? = error("not used")
        }

    private fun TestScope.readiness() = BuiltInChannelReadiness(source, clock, backgroundScope, { active })

    private fun airing(
        id: ChannelId,
        number: Int,
        name: String,
        hidden: HiddenReason? = null,
    ) = ChannelAiring(id, number, name, NOON, hidden)

    @Test
    fun aChannelThatFillsIsReadyAfterTheBuild() =
        runTest {
            val readiness = readiness()
            answer = { listOf(airing(neon, 352, "Neon Rain")) }

            readiness.switchedOn(ACCOUNT, neon)
            runCurrent()
            assertEquals(setOf(neon), readiness.pending.first())
            assertNull(readiness.notice.value)

            advanceTimeBy(5.seconds)
            assertEquals(emptySet(), readiness.pending.first())
            assertEquals(listOf(ReadyChannel(352, "Neon Rain")), readiness.notice.value?.channels)
        }

    @Test
    fun channelsSwitchedOnTogetherMakeOneNoticeAndOneBuild() =
        runTest {
            val readiness = readiness()
            answer = { listOf(airing(noir, 424, "Noir"), airing(neon, 352, "Neon Rain")) }

            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(100.milliseconds)
            readiness.switchedOn(ACCOUNT, noir)
            advanceTimeBy(5.seconds)

            assertEquals(1, calls.size)
            assertEquals(
                listOf(352, 424),
                readiness.notice.value
                    ?.channels
                    ?.map { it.number },
            )
        }

    @Test
    fun aChannelThatComesBackHiddenGetsNoNotice() =
        runTest {
            val readiness = readiness()
            answer = { listOf(airing(neon, 352, "Neon Rain", HiddenReason.EmptyToday)) }

            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(5.seconds)

            assertEquals(emptySet(), readiness.pending.first())
            assertNull(readiness.notice.value)
        }

    @Test
    fun aChannelSwitchedOffMeanwhileGetsNoNotice() =
        runTest {
            val readiness = readiness()
            answer = { emptyList() }

            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(5.seconds)

            assertEquals(emptySet(), readiness.pending.first())
            assertNull(readiness.notice.value)
        }

    @Test
    fun aSwitchOnForAnotherAccountGetsNoNotice() =
        runTest {
            val readiness = readiness()
            answer = { listOf(airing(neon, 352, "Neon Rain")) }
            active = null

            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(5.seconds)

            assertEquals(emptySet(), readiness.pending.first())
            assertNull(readiness.notice.value)
        }

    @Test
    fun aBuildThatKeepsFailingIsGivenUpOnWithoutANotice() =
        runTest {
            val readiness = readiness()
            answer = { error("the build failed") }

            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(5.seconds)

            assertEquals(3, calls.size)
            assertEquals(emptySet(), readiness.pending.first())
            assertNull(readiness.notice.value)
        }

    @Test
    fun theNoticeClearsItselfAndASecondBatchJoinsIt() =
        runTest {
            val readiness = readiness()
            answer = { listOf(airing(neon, 352, "Neon Rain")) }
            readiness.switchedOn(ACCOUNT, neon)
            advanceTimeBy(1.seconds)
            val first = readiness.notice.value
            assertEquals(1, first?.channels?.size)

            answer = { listOf(airing(noir, 424, "Noir")) }
            advanceTimeBy(5.seconds)
            readiness.switchedOn(ACCOUNT, noir)
            advanceTimeBy(1.seconds)
            val joined = readiness.notice.value
            assertEquals(listOf(352, 424), joined?.channels?.map { it.number })
            assertEquals(true, (joined?.id ?: 0) > (first?.id ?: 0))

            // The timer started over with the second batch: 8 seconds on from it, not from the first.
            advanceTimeBy(7.seconds)
            assertEquals(
                listOf(352, 424),
                readiness.notice.value
                    ?.channels
                    ?.map { it.number },
            )
            advanceTimeBy(2.seconds)
            assertNull(readiness.notice.value)
        }
}
