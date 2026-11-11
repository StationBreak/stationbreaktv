// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo

/** The guide's memory of decoded logos: the last 24 used, for one account at a time. */
class DecodedLogosTest {
    private fun logo(n: Int) = ItemLogo(ItemId("series$n"), "tag$n")

    @Test
    fun itHoldsTwentyFour() {
        assertEquals(24, DecodedLogos.CAPACITY)
    }

    @Test
    fun theLeastRecentlyUsedGoesFirstAndAGetCountsAsUse() {
        val cache = DecodedLogos()
        repeat(24) { cache.put("a", logo(it), StubBitmap()) }
        assertNotNull(cache.get("a", logo(0))) // 0 is now the most recently used; 1 the least

        cache.put("a", logo(24), StubBitmap())

        assertNull("the least recently used was dropped", cache.get("a", logo(1)))
        assertNotNull(cache.get("a", logo(0)))
        assertNotNull(cache.get("a", logo(2)))
        assertNotNull(cache.get("a", logo(24)))
    }

    @Test
    fun noMoreThanTheCapacityIsKept() {
        val cache = DecodedLogos(capacity = 3)
        repeat(10) { cache.put("a", logo(it), StubBitmap()) }

        assertEquals(listOf(7, 8, 9), (0 until 10).filter { cache.get("a", logo(it)) != null })
    }

    @Test
    fun aLogoPutAgainIsTheNewPicture() {
        val cache = DecodedLogos()
        val second = StubBitmap()
        cache.put("a", logo(1), StubBitmap())
        cache.put("a", logo(1), second)

        assertSame(second, cache.get("a", logo(1)))
    }

    @Test
    fun anotherAccountSeesNothingAndEmptiesWhatWasThere() {
        val cache = DecodedLogos()
        cache.put("a", logo(1), StubBitmap())

        assertNull(cache.get("b", logo(1)))
        assertNull("what account a had is gone", cache.get("a", logo(1)))
    }
}
