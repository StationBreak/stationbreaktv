// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Duration.Companion.seconds

/** Presses made at a cold launch, while the first day build is still running (#392, #399). */
class LiveChannelColdPressTest {
    private class Cold(
        val r: Rig,
        val job: Job,
        private val keys: Channel<LiveCommand>,
        private val scope: TestScope,
    ) {
        fun press(direction: FlipDirection) {
            keys.trySend(LiveCommand.Flip(direction))
            scope.runCurrent()
        }

        fun flips() = r.reports.filterIsInstance<TuneReport.Flip>().map { it.outcome }
    }

    /** Channels 2, 5 and 9 with the day build taking 20 s; the saved channel is 5. */
    private suspend fun TestScope.cold(
        saved: Boolean = true,
        setup: Rig.() -> Unit = {},
    ): Cold {
        val r = Rig(this)
        r.schedule.channel(CH2, 2, listOf(program(0, 600, 2)))
        r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
        r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
        if (saved) r.lastChannel.save(r.account, CH5)
        r.setup()
        r.tune.preResolveResult = { false }
        r.schedule.airingsDelayMs = 20_000
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        advanceTimeBy(1.seconds)
        // The later reads (the refresh, the flip's) are quick: only the first build was slow.
        r.schedule.airingsDelayMs = 0
        return Cold(r, job, keys, this)
    }

    @Test
    fun theBannerShowsFromTheLineupBeforeTheDayIsBuilt() =
        runTest {
            val c = cold()
            c.press(FlipDirection.Up)
            assertEquals(ChannelTag(9, "Test Channel 9"), (c.r.state as LiveState.Choosing).target)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Up)
            assertEquals(ChannelTag(2, "Test Channel 2"), (c.r.state as LiveState.Choosing).target)
            assertTrue("nothing is tuned yet", c.r.tunes.isEmpty())
        }

    @Test
    fun noPressIsDroppedAndTheFlipIsTheNetStepsFromTheAnchor() =
        runTest {
            val c = cold()
            c.press(FlipDirection.Up)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Up)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Down)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Up)
            // Up, up, down, up from 5 ends on 2. The build ends at 20 s; the first sitting lands on 5,
            // and the four presses are one flip.
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(5), itemOf(2)), c.r.tunes.map { it.item })
            // Up, up, down are three waited-out commits that the next press absorbed; the last is the flip itself.
            assertEquals(listOf("superseded", "superseded", "superseded", "playing"), c.flips())
        }

    @Test
    fun pressesTooCloseToHaveCommittedWriteNoLineOfTheirOwn() =
        runTest {
            val c = cold()
            c.press(FlipDirection.Up)
            c.press(FlipDirection.Up)
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(
                itemOf(2),
                c.r.tunes
                    .last()
                    .item,
            )
            assertEquals(listOf("playing"), c.flips())
        }

    @Test
    fun aRunEndingBeforeTheFirstSittingAbandonsTheCommittedPresses() =
        runTest {
            val c = cold()
            c.press(FlipDirection.Up)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Up)
            advanceTimeBy(2.seconds)
            c.job.cancel()
            runCurrent()
            assertEquals(listOf("abandoned", "abandoned"), c.flips())
            assertTrue(c.r.tunes.isEmpty())
        }

    @Test
    fun aLineupThatLandsAfterNoChannelsStillGetsTheEarlyPresses() =
        runTest {
            val c = cold { schedule.channels.values.forEach { it.blocks = emptyList() } }
            c.press(FlipDirection.Up)
            // The build ends with nothing on the air: the screen says so, under the banner.
            advanceTimeBy(30.seconds)
            c.r.schedule.channels.forEach { (_, e) ->
                e.blocks = listOf(program(0, 600, e.number))
            }
            c.r.channels.flow.value = emptyList()
            runCurrent()
            c.r.channels.publish(CH2 to 2, CH5 to 5, CH9 to 9)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(5), itemOf(9)), c.r.tunes.map { it.item })
            assertEquals(listOf("playing"), c.flips())
            assertTrue("the banner is gone", c.r.state !is LiveState.Choosing)
        }

    @Test
    fun anAbandonedPressLeavesNoBannerWhenTheLineupArrivesLater() =
        runTest {
            val c = cold()
            c.r.channels.flow.value = emptyList()
            c.press(FlipDirection.Up)
            // The build finds nothing; the next press meets the no-channels screen and abandons the one waiting.
            c.r.schedule.channels
                .forEach { (_, e) -> e.blocks = emptyList() }
            advanceTimeBy(30.seconds)
            c.press(FlipDirection.Up)
            c.r.channels.publish(CH2 to 2, CH5 to 5, CH9 to 9)
            runCurrent()
            assertTrue("no banner nobody pressed for", c.r.state !is LiveState.Choosing)
        }

    @Test
    fun withNoSavedChannelThereIsNoEarlyBannerAndThePressesStepFromTheLanding() =
        runTest {
            val c = cold(saved = false)
            c.press(FlipDirection.Up)
            assertTrue("no banner", c.r.state !is LiveState.Choosing)
            advanceTimeBy(30.seconds)
            runCurrent()
            // The first sitting lands on the lowest channel, 2; the Up steps to 5.
            assertEquals(listOf(itemOf(2), itemOf(5)), c.r.tunes.map { it.item })
        }

    @Test
    fun aFailedReadAfterAnchoringWritesALineForEveryEarlyPress() =
        runTest {
            val c = cold { schedule.airingsFailOn = setOf(2, 3) }
            c.press(FlipDirection.Up)
            advanceTimeBy(2.seconds)
            c.press(FlipDirection.Up)
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(listOf("superseded", "failed"), c.flips())
            assertTrue("the banner is gone", c.r.state !is LiveState.Choosing)
        }
}
