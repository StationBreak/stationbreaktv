// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.ChannelHoldingsReader
import tv.stationbreak.core.domain.lineup.HeldTitle
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

private const val HOUR_MS = 3_600_000L

/** What the channel box is told a channel holds (#757): read after a 200 ms rest on the column, for that channel. */
class GuideChannelHoldingsTest : GuideRig() {
    private class Holdings(
        private val held: Map<ChannelId, ChannelHoldings?>,
    ) : ChannelHoldingsReader {
        val reads = mutableListOf<ChannelId>()
        var fails = false

        override suspend fun read(
            id: ChannelId,
            at: Instant,
        ): ChannelHoldings? {
            reads += id
            check(!fails) { "the lineup is out of reach" }
            return held[id]
        }
    }

    private fun held(hours: Long) =
        ChannelHoldings(
            totalMs = hours * HOUR_MS,
            titleCount = 1,
            homeCount = 1,
            homes = listOf(HeldTitle(CanonicalId.parse("ab".repeat(16)), "A Show", hours * HOUR_MS, anchor = true)),
            top = emptyList(),
        )

    private val sevens = held(7)
    private val nines = held(9)
    private val reader = Holdings(mapOf(CH7 to sevens, CH9 to nines, CH5 to null))

    private fun GuideViewModel.press(direction: GuideDirection) = move(direction, repeat = false, atMs = 0)

    @Test
    fun theChannelBoxReadsAfterTheFocusHasRestedTwoHundredMilliseconds() {
        holdings = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            after(199)
            assertNull(vm.channelHoldings.value)
            after(1)
            assertEquals(CH7, vm.channelHoldings.value?.channel)
            assertEquals(sevens, vm.channelHoldings.value?.holdings)
            assertEquals(listOf(CH7), reader.reads)
        }
    }

    @Test
    fun movingToAnotherChannelDropsTheHoldingsAtOnceAndReadsTheNewOneAfterTheRest() {
        holdings = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            after(200)
            vm.press(GuideDirection.Down)
            runCurrent()
            assertNull(vm.channelHoldings.value)
            after(200)
            assertEquals(CH9, vm.channelHoldings.value?.channel)
            assertEquals(nines, vm.channelHoldings.value?.holdings)
        }
    }

    @Test
    fun aChannelPassedThroughBeforeTheRestIsNeverRead() {
        holdings = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            after(100)
            vm.press(GuideDirection.Down)
            after(200)
            assertEquals(listOf(CH9), reader.reads)
        }
    }

    @Test
    fun noHoldingsOffTheColumnAndNoReadThere() {
        holdings = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(500)
            assertNull(vm.channelHoldings.value)
            vm.press(GuideDirection.Left)
            after(200)
            vm.press(GuideDirection.Right)
            runCurrent()
            assertNull(vm.channelHoldings.value)
            after(500)
            assertEquals(listOf(CH7), reader.reads)
        }
    }

    @Test
    fun aChannelThatIsOffOrReadsBadlyShowsNone() {
        holdings = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            vm.press(GuideDirection.Up)
            assertEquals(CH5, vm.focused().channel)
            after(200)
            assertNull(vm.channelHoldings.value)

            reader.fails = true
            vm.press(GuideDirection.Down)
            after(200)
            assertNull(vm.channelHoldings.value)
            assertEquals(CH7, vm.focused().channel)
        }
    }

    @Test
    fun theFiguresAreTheHoldingsOwn() {
        val figures = held(412).figures()

        assertEquals(412L, figures.hours)
        assertEquals(17L, figures.days)
        assertEquals(listOf("A Show"), figures.names)
        assertEquals(true, figures.homeTo)
    }
}
