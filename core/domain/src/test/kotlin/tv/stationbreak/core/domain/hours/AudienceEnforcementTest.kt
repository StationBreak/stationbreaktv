// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.kids.KidsLockedChannelRepository
import tv.stationbreak.core.domain.kids.KidsLockedScheduleSource
import tv.stationbreak.core.domain.kids.NO_FLOOR
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** The hours of operation enforced through the Kids lock's own filters (#789, ADR-0033 "The seam"). */
@OptIn(ExperimentalCoroutinesApi::class)
class AudienceEnforcementTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val numbers = listOf(1, 33, 34, 66, 67, 99, 100, 150)

    private class FixedClock(
        var at: Instant,
    ) : Clock {
        override fun now() = at
    }

    private class Lineup(
        list: List<ChannelSummary>,
    ) : ChannelRepository {
        private val flow = MutableStateFlow(list)

        override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flow

        override fun activeChannels(): Flow<List<ChannelSummary>> = flow
    }

    private fun summary(number: Int) =
        ChannelSummary(
            ChannelId("ch-$number"),
            number,
            "Channel $number",
            ChannelStatus.On,
            ChannelKind.Starter,
            Instant.fromEpochMilliseconds(0),
            defaultNumber = number,
            kid = number < 100,
            optIn = false,
            playKey = PlayKey("test"),
            floor = NO_FLOOR,
        )

    // 2027-01-02 is a Saturday. The schedule: Saturday 20:00 to 21:00 Kids, 21:00 to 22:00 Off Air.
    private val schedule =
        HoursSchedule(
            listOf(
                HoursPeriod(SATURDAY, 20 * 60, 21 * 60, AudienceLevel.Kids),
                HoursPeriod(SATURDAY, 21 * 60, 22 * 60, AudienceLevel.OffAir),
            ),
        )
    private val pro = FakeProEntitlement(true)
    private val lock = FakeKidsLock(KidsLockState(enabled = false, cap = KidsCap.Teen, pinSet = false))
    private val store = InMemoryHoursStore(schedule)
    private val clock = FixedClock(Instant.parse("2027-01-02T19:30:00Z"))
    private val view = AudienceLockView(HoursOfOperation(store, pro, lock, clock) { utc })

    private val airings =
        FakeSchedule().apply {
            numbers.forEach { channel(ChannelId("ch-$it"), it, listOf(program(0, 60, it))) }
        }
    private val source = KidsLockedScheduleSource(airings, view)
    private val repository = KidsLockedChannelRepository(Lineup(numbers.map(::summary)), view)
    private val account = LibraryAccount(ServerId("srv1"), UserId("u"))
    private val now = Instant.fromEpochMilliseconds(T0 + 10 * MIN)

    private suspend fun aired() = source.airings(now).map { it.number }

    private suspend fun listed() = repository.activeChannels().first().map { it.number }

    private fun at(text: String) {
        clock.at = Instant.parse(text)
    }

    @Test
    fun outsideAnyPeriod_withNoLock_everythingShows() =
        runTest {
            assertEquals(numbers, aired())
            assertEquals(numbers, listed())
        }

    @Test
    fun aKidsPeriod_capsTheScheduleAndTheLineup() =
        runTest {
            at("2027-01-02T20:30:00Z")
            assertEquals(listOf(1, 33), aired())
            assertEquals(listOf(1, 33), listed())
            assertNull(source.onAir(ChannelId("ch-150"), now, 1))
            assertNotNull(source.onAir(ChannelId("ch-33"), now, 1))
        }

    @Test
    fun theLockAndThePeriod_theMoreRestrictiveWins() =
        runTest {
            at("2027-01-02T20:30:00Z")
            lock.lock(KidsCap.Kids)
            assertEquals(listOf(1, 33), aired())
            at("2027-01-02T19:30:00Z")
            lock.lock(KidsCap.Family)
            assertEquals(listOf(1, 33, 34, 66), aired())
        }

    @Test
    fun offAir_showsNothing_andNothingCanBeTuned() =
        runTest {
            at("2027-01-02T21:30:00Z")
            assertEquals(emptyList(), aired())
            assertEquals(emptyList(), listed())
            numbers.forEach { assertNull(source.onAir(ChannelId("ch-$it"), now, 1)) }
        }

    @Test
    fun thePin_overridesAnOffAirPeriod_untilTheLockComesBackOn() =
        runTest {
            at("2027-01-02T21:30:00Z")
            lock.current.value = KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = true)
            assertEquals(numbers, aired())
            assertEquals(numbers, listed())
            lock.lock(KidsCap.Teen) // the idle fallback
            assertEquals(emptyList(), aired())
        }

    @Test
    fun withProOff_theScheduleIsInert_andTheLockAloneDecides() =
        runTest {
            at("2027-01-02T21:30:00Z")
            pro.refund()
            assertEquals(numbers, aired())
            lock.lock(KidsCap.Family)
            assertEquals(listOf(1, 33, 34, 66), aired())
        }

    @Test
    fun aRefundKeepsTheStoredSchedule_andItAppliesAgainWithPro() =
        runTest {
            at("2027-01-02T21:30:00Z")
            pro.refund()
            assertEquals(schedule, store.current.value)
            pro.set(true)
            assertEquals(emptyList(), aired())
        }

    @Test
    fun withNoSchedule_nothingChanges() =
        runTest {
            store.current.value = HoursSchedule.EMPTY
            at("2027-01-02T21:30:00Z")
            assertEquals(numbers, aired())
            lock.lock(KidsCap.Teen)
            assertEquals(listOf(1, 33, 34, 66, 67, 99), aired())
        }

    @Test
    fun theLineupFollowsTheClockAcrossPeriodEdges_withNoOtherChange() =
        runTest {
            at("2027-01-02T19:59:30Z")
            val seen = mutableListOf<List<Int?>>()
            backgroundScope.launch { repository.channels(account).collect { seen += it.map { c -> c.number } } }
            runCurrent()
            assertEquals(listOf<List<Int?>>(numbers), seen)
            clock.at = Instant.parse("2027-01-02T20:00:00Z")
            advanceTimeBy(31_000)
            runCurrent()
            clock.at = Instant.parse("2027-01-02T21:00:00Z")
            advanceTimeBy(60_000)
            runCurrent()
            clock.at = Instant.parse("2027-01-02T22:00:00Z")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf<List<Int?>>(numbers, listOf(1, 33), emptyList(), numbers), seen)
        }
}
