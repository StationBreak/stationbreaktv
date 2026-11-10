// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.mark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which size of a bundled logo is decoded for a screen density (spec 27, the overhaul's item 5). */
class MarkBucketTest {
    private val all = setOf(32, 48, 64)

    @Test
    fun theSmallestBucketThatCoversTheBoxIsChosen() {
        // The 50 dp box needs 50 px at 1x (a 32 dp, 64 px file does), 75 at 1.5x (48 dp, 96 px), 100 at 2x (64 dp).
        assertEquals(32, pickMarkBucket(all, MARK_BOX_DP, 1f))
        assertEquals(48, pickMarkBucket(all, MARK_BOX_DP, 1.5f))
        assertEquals(64, pickMarkBucket(all, MARK_BOX_DP, 2f))
        assertEquals(64, pickMarkBucket(all, MARK_BOX_DP, 3f))
    }

    @Test
    fun aKeyWithOneSizeUsesItAtAnyDensity() {
        for (density in listOf(1f, 1.5f, 2f, 3f)) {
            assertEquals(32, pickMarkBucket(setOf(32), MARK_BOX_DP, density))
            assertEquals(64, pickMarkBucket(setOf(64), MARK_BOX_DP, density))
        }
    }

    @Test
    fun aMissingBucketFallsBackToTheNextLargerThenTheLargest() {
        assertEquals(64, pickMarkBucket(setOf(32, 64), MARK_BOX_DP, 1.5f))
        assertEquals(48, pickMarkBucket(setOf(32, 48), MARK_BOX_DP, 2f))
    }

    @Test
    fun noBucketsMeansNoLogo() {
        assertNull(pickMarkBucket(emptySet(), MARK_BOX_DP, 2f))
    }

    @Test
    fun theGeneratedFortyEightAndSixtyFourDpSetsFollowTheDensity() {
        // The generator makes 96 px and 128 px wide renders; the 50 dp box needs 50 px per density unit across.
        val generated = setOf(48, 64)
        assertEquals(48, pickMarkBucket(generated, MARK_BOX_DP, 1f))
        assertEquals(48, pickMarkBucket(generated, MARK_BOX_DP, 1.5f))
        assertEquals(48, pickMarkBucket(generated, MARK_BOX_DP, 1.9f))
        assertEquals(64, pickMarkBucket(generated, MARK_BOX_DP, 2f))
        assertEquals(64, pickMarkBucket(generated, MARK_BOX_DP, 3f))
    }
}
