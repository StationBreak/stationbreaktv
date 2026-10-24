// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** The channels a flip visits, and where Up and Down go from a number (spec 15). */
class FlipOrderTest {
    private fun channel(
        number: Int,
        hidden: HiddenReason? = null,
    ) = ChannelAiring(
        id = ChannelId("channel-$number"),
        number = number,
        name = "Test Channel $number",
        epochStart = Instant.fromEpochMilliseconds(0),
        hidden = hidden,
    )

    private fun order(vararg numbers: Int) = FlipOrder(numbers.map { channel(it) })

    @Test
    fun upGoesToTheNextNumber() {
        assertEquals(9, order(2, 5, 9).step(5, FlipDirection.Up)?.number)
    }

    @Test
    fun downGoesToThePreviousNumber() {
        assertEquals(2, order(2, 5, 9).step(5, FlipDirection.Down)?.number)
    }

    @Test
    fun upWrapsToTheFirst() {
        assertEquals(2, order(2, 5, 9).step(9, FlipDirection.Up)?.number)
    }

    @Test
    fun downWrapsToTheLast() {
        assertEquals(9, order(2, 5, 9).step(2, FlipDirection.Down)?.number)
    }

    @Test
    fun theListIsOrderedByNumberWhateverOrderItArrivesIn() {
        val flip = order(9, 2, 5)
        assertEquals(5, flip.step(2, FlipDirection.Up)?.number)
        assertEquals(3, flip.size)
    }

    @Test
    fun hiddenChannelsAreSkipped() {
        val flip = FlipOrder(listOf(channel(2), channel(5, HiddenReason.EmptyToday), channel(9)))
        assertEquals(9, flip.step(2, FlipDirection.Up)?.number)
        assertEquals(2, flip.step(9, FlipDirection.Down)?.number)
        assertEquals(2, flip.size)
        assertNull(flip.byId(ChannelId("channel-5")))
    }

    @Test
    fun aNumberNotInTheListStepsFromWhereItWouldBe() {
        val flip = order(2, 5, 9)
        assertEquals(9, flip.step(6, FlipDirection.Up)?.number)
        assertEquals(5, flip.step(6, FlipDirection.Down)?.number)
    }

    @Test
    fun aNumberPastTheEndsWraps() {
        val flip = order(2, 5, 9)
        assertEquals(2, flip.step(10, FlipDirection.Up)?.number)
        assertEquals(9, flip.step(1, FlipDirection.Down)?.number)
    }

    @Test
    fun aSingleChannelStepsToItself() {
        val flip = order(5)
        assertEquals(5, flip.step(5, FlipDirection.Up)?.number)
        assertEquals(5, flip.step(5, FlipDirection.Down)?.number)
    }

    @Test
    fun byIdFindsAVisibleChannel() {
        assertEquals(5, order(2, 5, 9).byId(ChannelId("channel-5"))?.number)
    }

    @Test
    fun emptyGivesNull() {
        val flip = FlipOrder(emptyList())
        assertNull(flip.step(1, FlipDirection.Up))
        assertNull(flip.step(1, FlipDirection.Down))
        assertTrue(flip.isEmpty)
        assertEquals(0, flip.size)
        assertFalse(order(1).isEmpty)
    }
}
