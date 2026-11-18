// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.BrandColors

/** The engine's own rules, apart from the fixture: time, colour tokens, the static and the shape stream. */
class StanEngineTest {
    private val brand =
        setOf(
            BrandColors.Cream,
            BrandColors.Ink,
            BrandColors.Orange,
            BrandColors.Grey,
            BrandColors.DarkGrey,
            BrandColors.Black,
        )

    @Test
    fun reducedMotionDrawsTheStillFrameAtThreeSeconds() {
        assertEquals(3.0, stanTimeSeconds(null, true, 9_000_000_000L), 0.0)
        assertEquals(3.0, stanTimeSeconds(0.5f, true, 0L), 0.0)
        assertEquals(3.0, STILL_FRAME_SECONDS, 0.0)
    }

    @Test
    fun withMotionOnTheCallersTimeWinsOverTheClock() {
        assertEquals(1.5, stanTimeSeconds(1.5f, false, 9_000_000_000L), 0.0)
        assertEquals(9.0, stanTimeSeconds(null, false, 9_000_000_000L), 0.0)
        assertEquals(0.0, stanTimeSeconds(null, false, 0L), 0.0)
    }

    @Test
    fun everyColourTokenOfEveryPaletteIsABrandToken() {
        for (palette in StanPalette.entries) {
            for (token in StanColor.entries) {
                assertTrue("$palette $token", palette.color(token) in brand)
            }
        }
    }

    @Test
    fun theDarkTokensAreCreamInkOrangeGreyAndBlack() {
        val p = StanPalette.Dark
        assertEquals(BrandColors.Cream, p.color(StanColor.Cream))
        assertEquals(BrandColors.Ink, p.color(StanColor.Ink))
        assertEquals(BrandColors.Orange, p.color(StanColor.Accent))
        assertEquals(BrandColors.Grey, p.color(StanColor.Grey))
        assertEquals(Color.Black, p.color(StanColor.Black))
    }

    @Test
    fun paperTradesInkAndCreamAndKeepsTheOrange() {
        val p = StanPalette.Paper
        assertEquals(BrandColors.Ink, p.color(StanColor.Cream))
        assertEquals(BrandColors.Cream, p.color(StanColor.Ink))
        assertEquals(BrandColors.Orange, p.color(StanColor.Accent))
        assertEquals(BrandColors.DarkGrey, p.color(StanColor.Grey))
    }

    @Test
    fun theGroundsAreTheBrandsLiftAndPaper() {
        assertEquals(BrandColors.ScreenLift, StanPalette.Dark.groundLift)
        assertEquals(BrandColors.ScreenEdge, StanPalette.Dark.groundEdge)
        assertEquals(BrandColors.Paper, StanPalette.Paper.groundLift)
        assertEquals(BrandColors.PaperEdge, StanPalette.Paper.groundEdge)
    }

    @Test
    fun theNoiseTilesAreSeededSoTheyAreTheSameEveryTime() {
        for (i in 0 until StanNoise.TILE_COUNT) {
            assertArrayEquals(StanNoise.tile(i), StanNoise.tile(i))
        }
        assertFalse(StanNoise.tile(0).contentEquals(StanNoise.tile(1)))
        assertNotEquals(StanNoise.tile(0)[0], StanNoise.tile(0)[1])
    }

    @Test
    fun aNoiseTileIs48By45GreyFrom40To240AndOpaque() {
        val tile = StanNoise.tile(2)
        assertEquals(48 * 45, tile.size)
        for (px in tile) {
            assertEquals(0xFF, px ushr 24)
            val red = (px shr 16) and 0xFF
            assertTrue(red in 40..240)
        }
    }

    @Test
    fun aDifferentSeedGivesDifferentStatic() {
        assertFalse(StanNoise.tile(0, seed = 1).contentEquals(StanNoise.tile(0, seed = 2)))
    }

    @Test
    fun everyPushAndClipIsClosedByAPopInEveryFrame() {
        for (scene in StanScene.entries) {
            for (step in 0..200) {
                var depth = 0
                for (shape in scene.frame(step * 0.05).stan) {
                    when (shape) {
                        is StanPush, is StanClip -> depth++
                        StanPop -> depth--
                        else -> Unit
                    }
                    assertTrue("${scene.key} step $step: pop without open group", depth >= 0)
                }
                assertEquals("${scene.key} step $step", 0, depth)
            }
        }
    }

    @Test
    fun snowTilesStayInRangeAndTheBlocksStayBetweenNoneAndSeven() {
        for (scene in StanScene.entries) {
            for (step in 0..400) {
                val frame = scene.frame(step * 0.05)
                frame.blocks?.let { assertTrue(it in 0..7) }
                frame.stan.filterIsInstance<StanSnow>().forEach {
                    assertTrue(it.tile in 0 until StanNoise.TILE_COUNT)
                    assertTrue(it.a in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun onlyTheStandbyCountsBlocksAndOnlyTheOpeningHasATitle() {
        for (scene in StanScene.entries) {
            val frame = scene.frame(3.0)
            assertEquals(scene == StanScene.Standby, frame.blocks != null)
            assertEquals(scene == StanScene.Opening, frame.title != null)
        }
    }

    @Test
    fun theRoundingHelpersRoundHalvesUpAndHaveNoNegativeZero() {
        assertEquals(0.3, r1(0.25), 0.0)
        assertEquals(-0.2, r1(-0.25), 0.0)
        assertEquals(0.13, r2(0.125), 0.0)
        assertEquals("0", num(r1(-0.04)))
        assertEquals("12", num(12.0))
        assertEquals("-3.5", num(-3.5))
    }
}
