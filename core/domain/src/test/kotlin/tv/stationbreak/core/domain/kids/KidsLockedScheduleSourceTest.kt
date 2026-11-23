// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.firstOnAir
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KidsLockedScheduleSourceTest {
    private val numbers = listOf(1, 33, 34, 66, 67, 99, 100, 150)
    private val schedule =
        FakeSchedule().apply {
            numbers.forEach { channel(ChannelId("ch-$it"), it, listOf(program(0, 60, it))) }
        }
    private val lock = FakeKidsLock()
    private val locked = KidsLockedScheduleSource(schedule, lock)
    private val now = Instant.fromEpochMilliseconds(T0 + 10 * MIN)

    private suspend fun airing() = locked.airings(now).map { it.number }

    @Test
    fun offEveryChannelAirs() =
        runTest {
            assertEquals(numbers, airing())
            numbers.forEach { assertNotNull(locked.onAir(ChannelId("ch-$it"), now, 1)) }
        }

    @Test
    fun eachCapAirsOnlyOneToItsNumber() =
        runTest {
            lock.lock(KidsCap.Kids)
            assertEquals(listOf(1, 33), airing())
            lock.lock(KidsCap.Family)
            assertEquals(listOf(1, 33, 34, 66), airing())
            lock.lock(KidsCap.Teen)
            assertEquals(listOf(1, 33, 34, 66, 67, 99), airing())
        }

    @Test
    fun aChannelAboveTheCapCannotBeTunedByIdEither() =
        runTest {
            lock.lock(KidsCap.Kids)
            assertNotNull(locked.onAir(ChannelId("ch-33"), now, 1))
            assertNull(locked.onAir(ChannelId("ch-34"), now, 1))
            assertNull(locked.onAir(ChannelId("ch-150"), now, 1))
            lock.unlock()
            assertNotNull(locked.onAir(ChannelId("ch-150"), now, 1))
        }

    @Test
    fun theGuideWindowShowsOnlyTheAllowedRows() =
        runTest {
            lock.lock(KidsCap.Family)
            val until = Instant.fromEpochMilliseconds(T0 + 60 * MIN)
            val window = assertNotNull(locked.schedule(now, until, ScheduleBuild.AsNeeded))
            assertEquals(listOf(1, 33, 34, 66), window.rows.map { it.channel.number })
        }

    @Test
    fun aChannelAboveTheCapHasNoStandIns() =
        runTest {
            schedule.standInScript = { block, _ -> listOf(block) }
            val block = program(0, 60, 150)
            lock.lock(KidsCap.Kids)
            assertTrue(locked.standIns(ChannelId("ch-150"), block, emptySet()).isEmpty())
            assertEquals(1, locked.standIns(ChannelId("ch-33"), program(0, 60, 33), emptySet()).size)
            lock.unlock()
            assertEquals(1, locked.standIns(ChannelId("ch-150"), block, emptySet()).size)
        }

    @Test
    fun theFirstChannelOnTheAirIsTheFirstAllowedOne() =
        runTest {
            lock.lock(KidsCap.Kids)
            // Even when the order asks for a grown-up channel first, it is not among those to choose from.
            val first = locked.firstOnAir(now) { visible -> visible.sortedByDescending { it.number } }
            assertEquals(33, first?.channel?.number)
        }

    // A stored lineup not yet moved up (#1097): numbered inside the cap but below its floor, it stays hidden and
    // cannot be tuned by id. A channel at or above its floor is unaffected, and the lock off shows everything.
    @Test
    fun aChannelBelowItsFloorIsHiddenAndUntunableWhateverItsNumber() =
        runTest {
            val adult = ChannelId("adult-40")
            val teens = ChannelId("teens-40")
            val placed = ChannelId("placed-40")
            schedule.channel(adult, 40, listOf(program(0, 60, 40))).floor = 100
            schedule.channel(teens, 40, listOf(program(0, 60, 41))).floor = 67
            schedule.channel(placed, 41, listOf(program(0, 60, 42))).floor = 34
            for (cap in listOf(KidsCap.Family, KidsCap.Teen)) {
                lock.lock(cap)
                val teen = if (cap == KidsCap.Teen) listOf(67, 99) else emptyList()
                assertEquals(listOf(1, 33, 34, 41, 66) + teen, airing().sorted())
                assertNull(locked.onAir(adult, now, 1), "adult at 40, $cap")
                assertNull(locked.onAir(teens, now, 1), "teens at 40, $cap")
                assertNotNull(locked.onAir(placed, now, 1), "placed at 41, $cap")
                assertTrue(locked.standIns(adult, locked.onAir(placed, now, 1)!!.now, emptySet()).isEmpty())
            }
            lock.unlock()
            assertNotNull(locked.onAir(adult, now, 1))
            assertTrue(airing().contains(40))
        }
}
