// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guide A colours, and the contrast pairs from the Guide A audit. */
class GuidePaletteTest {
    @Test
    fun darkAndLightUseTheOverhaulTokens() {
        val dark = GuideColors.Dark
        assertEquals(Color(0xFF0E0F11), dark.page)
        assertEquals(dark.page, dark.pageGradientStart)
        assertEquals(Color.White.copy(alpha = 0.06f), dark.card)
        assertEquals(Color(0xFFE8642C), dark.focusFill)
        assertEquals(Color(0xFF1A0D06), dark.focusText)
        assertEquals(Color.White.copy(alpha = 0.08f), dark.progressTrack)
        assertEquals(Color.White.copy(alpha = 0.90f), dark.textPrimary)
        assertEquals(Color.White.copy(alpha = 0.58f), dark.textSecondary)
        assertEquals(Color.White.copy(alpha = 0.38f), dark.textSupporting)
        assertEquals(dark.textPrimary, dark.programmeText)

        val light = GuideColors.Light
        assertEquals(Color(0xFFD2D5DA), light.page)
        assertEquals(light.page, light.pageGradientStart)
        assertEquals(Color(0xFF111214).copy(alpha = 0.12f), light.card)
        assertEquals(Color(0xFF111214).copy(alpha = 0.16f), light.line)
        assertEquals(Color(0xFF111214).copy(alpha = 0.14f), light.progressTrack)
        assertEquals(Color(0xFF111214).copy(alpha = 0.92f), light.textPrimary)
        assertEquals(Color(0xFF111214).copy(alpha = 0.66f), light.textSecondary)
        assertEquals(Color(0xFF111214).copy(alpha = 0.52f), light.textSupporting)
        assertEquals(Color(0xFFAD4216), light.focusFill)
        assertEquals(Color(0xFFFFFFFF), light.focusText)
        assertEquals(light.textPrimary, light.programmeText)
    }

    @Test
    fun noWarmTintsRemain() {
        // Every neutral role is grey: equal red, green and blue to within the 1-2 steps the near-black page keeps.
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            for (neutral in listOf(c.card, c.textPrimary, c.textSecondary, c.textSupporting, c.progressTrack)) {
                val channels = listOf(neutral.red, neutral.green, neutral.blue)
                val spread = channels.max() - channels.min()
                assertTrue("neutral $neutral is tinted", spread <= NEUTRAL_SPREAD)
            }
            // The page may lean cool (Light's blue-grey reads calmer on a TV than a pure grey) but never warm.
            val spread = listOf(c.page.red, c.page.green, c.page.blue).let { it.max() - it.min() }
            assertTrue("page ${c.page} is tinted", spread <= PAGE_SPREAD)
            assertTrue("page ${c.page} is warm", c.page.blue >= c.page.red)
        }
    }

    @Test
    fun everyAuditedPairClearsItsMinimum() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            val card = c.card.compositeOver(c.page)
            assertContrast(3.0, c.focusFill, card, "$name focus on a card", c.page)
            assertContrast(3.0, c.focusFill, c.page, "$name focus on the page", c.page)
            assertContrast(4.5, c.focusText, c.focusFill, "$name focus text", c.page)
            // The focused cell's 11 sp second line.
            assertContrast(4.5, c.focusTextSecondary, c.focusFill, "$name focused second line", c.page)
            assertContrast(4.5, c.textPrimary, c.page, "$name primary text", c.page)
            assertContrast(4.5, c.textPrimary, card, "$name primary text on a card", c.page)
            assertContrast(4.5, c.textSecondary, c.page, "$name secondary text", c.page)
            assertContrast(4.5, c.textSecondary, card, "$name secondary text on a card", c.page)
            assertContrast(4.5, c.programmeText, card, "$name programme text on a card", c.page)
            // The panel's 10 sp ON NOW is accent text on the page, no longer on a chip.
            assertContrast(4.5, c.accent, c.page, "$name accent text (ON NOW)", c.page)
            assertContrast(3.0, c.progressFill, c.progressTrack.compositeOver(c.page), "$name progress", c.page)
            // The panel's 2 dp progress line: the accent over its 10% track.
            assertContrast(3.0, c.progressFill, c.line.compositeOver(c.page), "$name panel progress", c.page)
            // Tertiary is for strokes (the chevrons), not text: 3:1.
            assertContrast(3.0, c.textSupporting, c.page, "$name tertiary text", c.page)
            assertContrast(3.0, c.textSupporting, card, "$name tertiary on a card (chevrons)", c.page)
            // The progress line on a card, and its focused form on the focus fill.
            assertContrast(3.0, c.progressFill, c.progressTrack.compositeOver(card), "$name progress on a card", c.page)
            val focusedTrack = c.focusText.copy(alpha = 0.2f).compositeOver(c.focusFill)
            assertContrast(3.0, c.focusText, focusedTrack, "$name focused progress", c.page)
        }
    }

    @Test
    fun thePanelsFollowTheGuidesTheme() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            val panel = c.panel
            assertEquals(c.page, panel.ground)
            assertEquals(c.textPrimary, panel.text)
            assertEquals(c.textSecondary, panel.secondary)
            assertEquals(c.focusFill, panel.ring)
            assertContrast(4.5, panel.text, panel.ground, "$name panel text", c.page)
            assertContrast(4.5, panel.secondary, panel.ground, "$name panel secondary text", c.page)
            assertContrast(3.0, panel.ring, panel.ground, "$name panel focus ring", c.page)
        }
        assertTrue(
            GuideColors.Light.panel.ground
                .luminance() >
                GuideColors.Dark.panel.ground
                    .luminance(),
        )
    }

    @Test
    fun tertiaryIsForStrokesNotText() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            val card = c.card.compositeOver(c.page)
            assertContrast(3.0, c.textSupporting, c.page, "$name tertiary over the page", c.page)
            assertContrast(3.0, c.textSupporting, card, "$name tertiary over a card", c.page)
            // It does not reach the 4.5:1 text needs, so it must not be used for text.
            val seen = c.textSupporting.compositeOver(c.page).luminance()
            val ratio = (maxOf(seen, c.page.luminance()) + 0.05) / (minOf(seen, c.page.luminance()) + 0.05)
            assertTrue("$name tertiary is $ratio: if it now clears 4.5, this guard can go", ratio < 4.5)
        }
        // Only the cells (chevrons) read it: any other file that does is drawing text with it.
        val readers =
            File("src/main/kotlin/tv/stationbreak/feature/guide")
                .walkTopDown()
                .filter { it.extension == "kt" && it.name != "GuidePalette.kt" }
                .filter { "textSupporting" in it.readText() }
                .map { it.name }
                .toList()
        assertEquals(listOf("GuideCells.kt"), readers)
    }

    @Test
    fun theChannelNumberKeepsItsContrastOnThePage() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            assertContrast(4.5, c.textSecondary, c.page, "$name channel number", c.page)
            assertContrast(4.5, c.textPrimary, c.page, "$name playing channel number", c.page)
        }
    }

    @Test
    fun noPillsOrChipsRemain() {
        // The menu is text tabs and the NOW chip is text: the tokens for their fills are gone, and the key cap is an
        // outline whose label is primary text.
        assertEquals(0.28f, GuideColors.Dark.keyCapStroke.alpha, 0.01f)
        // Light's cap outline is its line.
        assertEquals(GuideColors.Light.line, GuideColors.Light.keyCapStroke)
    }

    @Test
    fun theNowLineIsFaintAndTheTimelineRulesAreFainter() {
        assertEquals(0.35f, GuideColors.Dark.nowRailAlpha)
        assertEquals(0.10f, GuideColors.Dark.line.alpha, 0.01f)
        // On the grey page 35% of the accent is lost, so Light's now-line and triangle are stronger.
        assertEquals(0.60f, GuideColors.Light.nowRailAlpha)
        assertEquals(0.16f, GuideColors.Light.line.alpha, 0.01f)
    }

    @Test
    fun noPageIsBlindingWhite() {
        // The Insignia showed the near-white page as glare: no page, or the gradient's start, may be lighter than 90%.
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            for ((role, page) in listOf("page" to c.page, "gradient start" to c.pageGradientStart)) {
                val lightness = (maxOf(page.red, page.green, page.blue) + minOf(page.red, page.green, page.blue)) / 2f
                assertTrue("$name $role is $lightness light, over $MAX_PAGE_LIGHTNESS", lightness <= MAX_PAGE_LIGHTNESS)
            }
        }
        assertEquals("Light has no gradient", GuideColors.Light.page, GuideColors.Light.pageGradientStart)
    }

    @Test
    fun theTextAndCardAreTheOnlyAlphaRoles() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            listOf(c.page, c.accent, c.focusFill, c.focusText, c.nowLine, c.progressFill).forEach {
                assertEquals(1f, it.alpha)
            }
        }
    }

    @Test
    fun theSavedChoiceIsDarkUnlessItIsLight() {
        assertEquals(GuideTheme.Dark, GuideTheme.decode(null))
        assertEquals(GuideTheme.Dark, GuideTheme.decode(""))
        assertEquals(GuideTheme.Dark, GuideTheme.decode("system"))
        assertEquals(GuideTheme.Dark, GuideTheme.decode("Dark"))
        assertEquals(GuideTheme.Light, GuideTheme.decode(GuideTheme.encode(GuideTheme.Light)))
        assertEquals(GuideColors.Dark, GuideTheme.Dark.colors)
        assertEquals(GuideColors.Light, GuideTheme.Light.colors)
    }

    @Test
    fun focusIsAFillChangeOnly() {
        assertEquals(1f, CONTENT_SCALE)
        assertEquals(0f, GuideFocusExtendX.value)
        assertEquals(0f, GuideFocusExtendY.value)
        assertTrue("nothing in the grid is rounder than 2 dp", GuideCardRadius.value <= 2f)
    }

    @Test
    fun theFocusBoundsStillGrowByAnExtent() {
        assertEquals(1f, CONTENT_SCALE)
        val inset = 4f
        val resting =
            guideFocusBounds(100f, 40f, FocusExtent(inset = inset, extendX = 4f, extendY = 2f, amount = 0f))
        assertEquals(inset, resting.left)
        assertEquals(100f - inset, resting.right)
        assertEquals(inset, resting.top)
        assertEquals(40f - inset, resting.bottom)
        val focused =
            guideFocusBounds(100f, 40f, FocusExtent(inset = inset, extendX = 4f, extendY = 2f, amount = 1f))
        assertEquals(0f, focused.left)
        assertEquals(100f, focused.right)
        assertEquals(2f, focused.top)
        assertEquals(38f, focused.bottom)
    }

    /** WCAG ratio of [a] over [page] against [b] over [page], so a colour with an alpha is judged as it is seen. */
    @Test
    fun onlyLightTintsTheChannelMarks() {
        assertEquals(null, GuideColors.Dark.markTint)
        val ink = GuideColors.Light.markTint
        assertTrue("Light tints the mono marks", ink != null)
        assertEquals("the tint is opaque ink", 1f, ink!!.alpha, 0f)
        assertTrue("the ink is dark", ink.luminance() < 0.05f)
    }

    private fun assertContrast(
        minimum: Double,
        a: Color,
        b: Color,
        what: String,
        page: Color,
    ) {
        val behind = b.compositeOver(page)
        val (light, dark) = listOf(a.compositeOver(behind).luminance(), behind.luminance()).sortedDescending()
        val ratio = (light + 0.05) / (dark + 0.05)
        assertTrue("$what is $ratio, needs $minimum", ratio >= minimum)
    }
}

private const val NEUTRAL_SPREAD = 0.02f
private const val PAGE_SPREAD = 0.035f
private const val MAX_PAGE_LIGHTNESS = 0.90f
