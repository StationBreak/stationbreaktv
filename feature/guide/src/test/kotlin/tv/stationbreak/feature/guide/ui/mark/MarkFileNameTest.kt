// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Asset names are only ever taken from the strict pattern; anything else yields no key (spec 27). */
class MarkFileNameTest {
    @Test
    fun theKeyIsTakenFromAThirtyTwoDpName() {
        assertEquals("little-farm", markKeyOf("sl_004_little-farm_32dp.png"))
    }

    @Test
    fun theBucketsAreThirtyTwoFortyEightAndSixtyFourDp() {
        for (n in MarkBuckets) {
            assertEquals("little-farm", markKeyOf("sl_004_little-farm_${n}dp.png"))
            assertEquals(n, markBucketOf("sl_004_little-farm_${n}dp.png"))
        }
        assertNull(markBucketOf("sl_004_little-farm_40dp.png"))
    }

    @Test
    fun anyOtherNameHasNoKey() {
        assertNull(markKeyOf("sl_004_little-farm_40dp.png"))
        assertNull(markKeyOf("sl_004_little-farm_128dp.png"))
        assertNull(markKeyOf("readme.md"))
        assertNull(markKeyOf(""))
        assertNull(markKeyOf("sl_4_x_32dp.png"))
    }

    @Test
    fun pathCharactersAndUpperCaseHaveNoKey() {
        assertNull(markKeyOf("sl_004_../x_32dp.png"))
        assertNull(markKeyOf("sl_004_a/b_32dp.png"))
        assertNull(markKeyOf("../sl_004_x_32dp.png"))
        assertNull(markKeyOf("sl_004_Little_32dp.png"))
        assertNull(markKeyOf("sl_004_x_32dp.png.bak"))
        assertNull(markKeyOf("sl_004_x_32dp.png\nextra"))
    }
}
