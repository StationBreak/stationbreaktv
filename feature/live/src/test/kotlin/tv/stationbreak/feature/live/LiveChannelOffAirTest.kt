// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId

/**
 * The hours of operation on live TV (#789, ADR-0033): a period that has the TV off the air ([KidsLockState.offAir])
 * stops what plays and shows the off-the-air card; when it ends, or the PIN turns the lock off, live tunes again.
 */
class LiveChannelOffAirTest {
    private val ch5 = ChannelId("ch-5")
    private val ch150 = ChannelId("ch-150")
    private val offAir = KidsLockState(enabled = true, cap = KidsCap.DEFAULT, pinSet = false, offAir = true)
    private val open = KidsLockState(enabled = false, cap = KidsCap.DEFAULT, pinSet = true)

    private fun rig(scope: kotlinx.coroutines.test.TestScope): Rig =
        Rig(scope, kidsLock = FakeKidsLock(open, pin = "1234")).apply {
            schedule.channel(ch5, 5, listOf(program(0, 120, 5)))
            schedule.channel(ch150, 150, listOf(program(0, 120, 150)))
        }

    @Test
    fun offAirWhileWatching_stopsPlaybackAndShowsTheOffTheAirCard() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            r.start()
            r.atMin(5)
            assertTrue(r.state is LiveState.Watching)
            r.kidsLock!!.current.value = offAir
            r.atMin(6)
            assertEquals(LiveState.Ident(upNext = null), r.state)
            assertEquals(1, r.tune.calls.count { it is Call.Tune })
        }

    @Test
    fun whenThePeriodEnds_liveTunesAgain() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            r.start()
            r.atMin(5)
            r.kidsLock!!.current.value = offAir
            r.atMin(6)
            r.kidsLock.current.value = open
            r.atMin(7)
            assertTrue(r.state is LiveState.Watching)
            assertEquals(
                listOf(itemOf(150), itemOf(150)),
                r.tune.calls
                    .filterIsInstance<Call.Tune>()
                    .map { it.item },
            )
        }

    @Test
    fun startingInsideAnOffAirPeriod_showsTheCardAndTunesNothing() =
        runTest {
            val r = rig(this)
            r.kidsLock!!.current.value = offAir
            r.start()
            r.atMin(1)
            assertEquals(LiveState.Ident(upNext = null), r.state)
            assertTrue(r.tune.calls.none { it is Call.Tune })
        }
}
