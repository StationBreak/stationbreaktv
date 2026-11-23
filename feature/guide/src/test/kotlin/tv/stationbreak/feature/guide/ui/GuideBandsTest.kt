// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.NumberBand
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.feature.guide.GuideRow

class GuideBandsTest {
    private fun rows(vararg numbers: Int) = numbers.map { GuideRow(ChannelId("c$it"), it, "Channel $it", emptyList()) }

    @Test
    fun aRowKnowsItsBandFromItsNumber() {
        val bands = rows(33, 34, 67, 100).map { it.band }
        assertEquals(listOf(NumberBand.Tots, NumberBand.Kids, NumberBand.Teens, NumberBand.Adults), bands)
        assertNull(rows(0).single().band)
    }

    @Test
    fun aDividerStartsWhereTheBandChangesBetweenTwoVisibleRows() {
        val all = rows(32, 33, 34, 35, 66, 67, 99, 100)
        val starts = all.indices.map { dividerBandAt(all, it, firstVisibleRow = 0) }
        assertEquals(
            listOf(null, null, NumberBand.Kids, null, null, NumberBand.Teens, null, NumberBand.Adults),
            starts,
        )
    }

    @Test
    fun theFirstVisibleRowHasNoDividerAboveItEvenWhereABandStarts() {
        val all = rows(33, 34, 35)
        assertNull(dividerBandAt(all, index = 1, firstVisibleRow = 1))
        assertEquals(NumberBand.Kids, dividerBandAt(all, index = 1, firstVisibleRow = 0))
    }

    @Test
    fun noRowOrABandlessRowStartsNoDivider() {
        val all = rows(33, 0)
        assertNull(dividerBandAt(all, index = 1, firstVisibleRow = 0))
        assertNull(dividerBandAt(all, index = 5, firstVisibleRow = 0))
    }

    @Test
    fun adultsHasNoStripeAndTheYoungBandsTakeTheirAccent() {
        for (colors in listOf(GuideColors.Dark, GuideColors.Light)) {
            assertNull(stripeOf(NumberBand.Adults, colors))
            assertNull(stripeOf(null, colors))
            assertEquals(colors.band.tots, stripeOf(NumberBand.Tots, colors))
            assertEquals(colors.band.kids, stripeOf(NumberBand.Kids, colors))
            assertEquals(colors.band.teens, stripeOf(NumberBand.Teens, colors))
            assertEquals(colors.band.adults, colors.band.of(NumberBand.Adults))
        }
    }
}
