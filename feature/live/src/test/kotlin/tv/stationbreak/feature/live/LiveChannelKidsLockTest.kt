// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId

/**
 * Kids Mode (#789) on live TV: while the lock is on, no channel above its cap is tuned, whether it was the last channel
 * watched, the one playing when the lock came on, a guide pick or the channel before this one. When the remembered or
 * playing channel is hidden, live tunes the first allowed channel (the lowest number with something on).
 */
class LiveChannelKidsLockTest {
    private val ch5 = ChannelId("ch-5")
    private val ch20 = ChannelId("ch-20")
    private val ch150 = ChannelId("ch-150")

    // Each channel numbered N plays item N for the first two hours. Channel 20 has the PG-tier ceiling the app's
    // usual first pick prefers, and is the neighbour below 150: neither rule may pick it while the lock hides 150.
    private fun rig(
        scope: kotlinx.coroutines.test.TestScope,
        lock: FakeKidsLock = FakeKidsLock(pin = "1234"),
    ): Rig =
        Rig(scope, kidsLock = lock).apply {
            schedule.channel(ch5, 5, listOf(program(0, 120, 5)))
            schedule.channel(ch20, 20, listOf(program(0, 120, 20))).ceilingRank = ChannelAiring.PG_TIER
            schedule.channel(ch150, 150, listOf(program(0, 120, 150)))
        }

    // A tune is a fresh one, or the look-ahead's handle taken at a commit.
    private fun Rig.allTuned() =
        tune.calls.mapNotNull {
            when (it) {
                is Call.Tune -> it.item
                is Call.TuneHandle -> it.item
                else -> null
            }
        }

    @Test
    fun aLastChannelAboveTheCapStartsOnTheFirstAllowedChannel() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            r.kidsLock!!.lock(KidsCap.Kids)
            r.start()
            assertEquals(listOf(itemOf(5)), r.allTuned())
            assertEquals(ch5, r.lastChannel.load(r.account))
        }

    @Test
    fun aLastChannelWithinTheCapIsStillRestored() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch20, 20)
            r.kidsLock!!.lock(KidsCap.Kids)
            r.start()
            assertEquals(listOf(itemOf(20)), r.allTuned())
        }

    @Test
    fun withTheLockOffTheLastChannelIsRestoredWhateverItsNumber() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            r.start()
            assertEquals(listOf(itemOf(150)), r.allTuned())
        }

    @Test
    fun turningTheLockOnWhileAChannelAboveTheCapPlaysTunesTheFirstAllowedOneAtOnce() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            r.start()
            r.atMin(5)
            r.kidsLock!!.lock(KidsCap.Kids)
            r.atMin(6)
            // Long before the show on 150 ends (minute 110 of the test).
            assertEquals(listOf(itemOf(150), itemOf(5)), r.allTuned())
            assertEquals(ch5, r.lastChannel.load(r.account))
        }

    @Test
    fun loweringTheCapBelowThePlayingChannelTunesAway() =
        runTest {
            val r = rig(this)
            val ch50 = ChannelId("ch-50")
            r.schedule.channel(ch50, 50, listOf(program(0, 120, 50)))
            r.lastChannel.save(r.account, ch50, 50)
            r.kidsLock!!.lock(KidsCap.Teen)
            r.start()
            r.atMin(5)
            r.kidsLock.lock(KidsCap.Kids)
            r.atMin(6)
            assertEquals(listOf(itemOf(50), itemOf(5)), r.allTuned())
        }

    @Test
    fun turningTheLockOnWhileAnAllowedChannelPlaysLeavesItPlaying() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch5, 5)
            r.start()
            r.atMin(5)
            r.kidsLock!!.lock(KidsCap.Kids)
            r.atMin(6)
            assertEquals(listOf(itemOf(5)), r.allTuned())
            assertTrue(r.state is LiveState.Watching)
        }

    @Test
    fun aGuidePickAboveTheCapIsNotTuned() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch5, 5)
            r.kidsLock!!.lock(KidsCap.Kids)
            val commands = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = commands.receiveAsFlow())
            r.atMin(1)
            commands.trySend(LiveCommand.Tune(ch150))
            r.atMin(2)
            assertEquals(listOf(itemOf(5)), r.allTuned())
        }

    @Test
    fun theChannelBeforeThisOneIsNotTunedWhenItIsAboveTheCap() =
        runTest {
            val r = rig(this)
            r.lastChannel.save(r.account, ch150, 150)
            val commands = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = commands.receiveAsFlow())
            r.atMin(1)
            commands.trySend(LiveCommand.Tune(ch5))
            r.atMin(2)
            r.kidsLock!!.lock(KidsCap.Kids)
            r.atMin(3)
            // Menu would go back to 150.
            commands.trySend(LiveCommand.Previous)
            r.at(4 * MIN)
            assertEquals(listOf(itemOf(150), itemOf(5)), r.allTuned())
        }
}
