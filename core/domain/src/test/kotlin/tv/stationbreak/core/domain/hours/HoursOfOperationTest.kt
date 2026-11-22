// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockState
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HoursOfOperationTest {
    private val ny = TimeZone.getTimeZone("America/New_York")
    private val nyZone: ZoneId = ny.toZoneId()

    private fun instant(local: ZonedDateTime) = Instant.fromEpochMilliseconds(local.toInstant().toEpochMilli())

    private val nightlyKids = HoursSchedule(listOf(HoursPeriod(SATURDAY, 22 * 60, 6 * 60, AudienceLevel.Kids)))

    private class FixedClock(
        var at: Instant,
    ) : Clock {
        override fun now() = at
    }

    private fun at(text: String) = Instant.parse(text)

    private val pro = FakeProEntitlement(true)
    private val lock = FakeKidsLock(KidsLockState(enabled = true, cap = KidsCap.Teen, pinSet = true), pin = "1234")
    private val store = InMemoryHoursStore(nightlyKids)
    private val clock = FixedClock(at("2027-01-02T23:00:00-05:00")) // Saturday 23:00 in New York

    private fun hours() = HoursOfOperation(store, pro, lock, clock) { ny }

    private fun locked(cap: KidsCap) {
        lock.current.value = KidsLockState(enabled = true, cap = cap, pinSet = true)
    }

    private suspend fun now() = hours().audience().first()

    @Test
    fun effective_isTheMoreRestrictiveOfThePeriodAndTheLock() {
        fun e(
            period: AudienceLevel?,
            cap: KidsCap,
        ) = effectiveAudience(period, KidsLockState(enabled = true, cap = cap, pinSet = true))
        assertEquals(AudienceLevel.Kids, e(AudienceLevel.Kids, KidsCap.Teen))
        assertEquals(AudienceLevel.Kids, e(AudienceLevel.Teen, KidsCap.Kids))
        assertEquals(AudienceLevel.Teen, e(AudienceLevel.Adult, KidsCap.Teen))
        assertEquals(AudienceLevel.OffAir, e(AudienceLevel.OffAir, KidsCap.Teen))
        assertEquals(AudienceLevel.Family, e(null, KidsCap.Family))
    }

    @Test
    fun noPeriod_andNoLock_isAdult_theFreeBehaviour() {
        assertEquals(AudienceLevel.Adult, effectiveAudience(null, KidsLockState(false, KidsCap.Kids, false)))
    }

    @Test
    fun aPeriodAppliesWithTheLockOnAndOffWithNoPin() =
        runTest {
            locked(KidsCap.Teen)
            assertEquals(AudienceLevel.Kids, now())
            lock.current.value = KidsLockState(enabled = false, cap = KidsCap.Teen, pinSet = false)
            assertEquals(AudienceLevel.Kids, now())
        }

    @Test
    fun theUnlockedPin_overridesThePeriod_evenOffAir() =
        runTest {
            store.current.value = HoursSchedule(listOf(HoursPeriod(SATURDAY, 0, 1440, AudienceLevel.OffAir)))
            locked(KidsCap.Kids)
            assertEquals(AudienceLevel.OffAir, now())
            lock.unlock()
            assertEquals(AudienceLevel.Adult, now())
            locked(KidsCap.Kids) // the idle fallback turns the lock back on: the period applies again
            assertEquals(AudienceLevel.OffAir, now())
        }

    @Test
    fun outsideAnyPeriod_theLockAloneDecides() =
        runTest {
            clock.at = at("2027-01-03T12:00:00-05:00") // Sunday noon
            locked(KidsCap.Family)
            assertEquals(AudienceLevel.Family, now())
        }

    @Test
    fun withProOff_theScheduleIsInert_isKept_andAppliesAgainOnUnlock() =
        runTest {
            locked(KidsCap.Teen)
            pro.refund()
            assertEquals(AudienceLevel.Teen, now())
            assertEquals(HoursEdit.ProRequired, hours().edit(HoursSchedule.EMPTY))
            assertEquals(nightlyKids, store.current.value)
            assertEquals(nightlyKids, hours().stored.first())
            pro.set(true)
            assertEquals(AudienceLevel.Kids, now())
        }

    @Test
    fun theGateIsReadAtEachCheck() =
        runTest {
            val h = hours()
            pro.set(false)
            assertEquals(HoursEdit.ProRequired, h.edit(nightlyKids))
            pro.set(true)
            assertEquals(HoursEdit.Saved, h.edit(HoursSchedule.EMPTY))
            assertEquals(HoursSchedule.EMPTY, store.current.value)
        }

    @Test
    fun editNeedsAPin_andReportsAFailedWrite() =
        runTest {
            lock.current.value = KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = false)
            assertEquals(HoursEdit.NoPin, hours().edit(HoursSchedule.EMPTY))
            lock.current.value = KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = true)
            store.failWrites = true
            assertEquals(HoursEdit.Unavailable, hours().edit(HoursSchedule.EMPTY))
            assertEquals(nightlyKids, store.current.value)
        }

    @Test
    fun emptySchedule_hasNoEffect() =
        runTest {
            store.current.value = HoursSchedule.EMPTY
            locked(KidsCap.Family)
            assertEquals(AudienceLevel.Family, now())
        }

    @Test
    fun springForward_theNightIsOneHourShorterInRealTime_butTheLocalEdgeHolds() =
        runTest {
            // New York 2027-03-14: 02:00 EST jumps to 03:00 EDT. The period runs Saturday 22:00 to Sunday 06:00 local.
            val h = hours()
            clock.at = instant(ZonedDateTime.of(2027, 3, 14, 5, 59, 0, 0, nyZone))
            assertEquals(AudienceLevel.Kids, h.audience().first())
            clock.at = instant(ZonedDateTime.of(2027, 3, 14, 6, 0, 0, 0, nyZone))
            assertEquals(AudienceLevel.Teen, h.audience().first())
        }

    @Test
    fun fallBack_theRepeatedHourGetsTheSameLevelBothTimes() =
        runTest {
            // New York 2027-11-07: 01:30 happens twice (EDT then EST). Sunday 00:30 to 02:00 Off Air.
            store.current.value = HoursSchedule(listOf(HoursPeriod(SUNDAY, 30, 2 * 60, AudienceLevel.OffAir)))
            val h = hours()
            val first = ZonedDateTime.of(2027, 11, 7, 1, 30, 0, 0, nyZone)
            val second = first.withLaterOffsetAtOverlap()
            assertEquals(first.toLocalTime(), second.toLocalTime())
            listOf(first, second).forEach {
                clock.at = instant(it)
                assertEquals(AudienceLevel.OffAir, h.audience().first())
            }
            clock.at =
                Instant.fromEpochMilliseconds(
                    second
                        .withHour(2)
                        .withMinute(0)
                        .toInstant()
                        .toEpochMilli(),
                )
            assertEquals(AudienceLevel.Teen, h.audience().first())
        }

    @Test
    fun theAnswerChangesAtTheMinuteAPeriodEnds_withNoOtherChange() =
        runTest {
            clock.at = at("2027-01-03T05:58:30-05:00") // Sunday 05:58:30, inside Saturday's overnight period
            val seen = mutableListOf<AudienceLevel>()
            val job = backgroundScope.launch { hours().audience().toList(seen) }
            runCurrent()
            assertEquals(listOf(AudienceLevel.Kids), seen)
            clock.at = at("2027-01-03T06:00:00-05:00")
            advanceTimeBy(91_000)
            runCurrent()
            assertEquals(listOf(AudienceLevel.Kids, AudienceLevel.Teen), seen)
            job.cancel()
        }
}
