// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guide A colours, and the contrast pairs from the Guide A audit. */
class GuideColorsTest {
    @Test
    fun darkAndLightUseTheOverhaulTokens() {
        val dark = GuideColors.Dark
        assertEquals(Color(0xFF1C1B19), dark.page)
        assertEquals(dark.page, dark.pageGradientStart)
        assertEquals(Color(0xFF262320), dark.card)
        assertEquals(Color(0xFFE8642C), dark.focusFill)
        assertEquals(Color(0xFFE8642C), dark.accent)
        assertEquals(Color(0xFFE8642C), dark.nowLine)
        assertEquals(Color(0xFFE8642C), dark.progressFill)
        assertEquals(Color(0xFF1C1B19), dark.focusText)
        assertEquals(Color(0xFF1C1B19).copy(alpha = 0.90f), dark.focusTextSecondary)
        assertEquals(Color(0xFF3D3832), dark.progressTrack)
        assertEquals(Color(0xFF3D3832), dark.line)
        assertEquals(Color(0xFFCFCBC2), dark.keyCapStroke)
        assertEquals(Color(0xFFF3EEE3), dark.textPrimary)
        assertEquals(Color(0xFFB3AB9D), dark.textSecondary)
        assertEquals(Color(0xFFB3AB9D), dark.textSupporting)
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
    fun darkIsWarmAndLightStaysNeutral() {
        // Dark's neutrals all lean warm (red above blue), as the brand's ink does.
        val dark = GuideColors.Dark
        val darkNeutrals =
            listOf(dark.page, dark.card, dark.textPrimary, dark.textSecondary, dark.textSupporting, dark.line)
        for (neutral in darkNeutrals) {
            assertTrue("dark neutral $neutral is not warm", neutral.red > neutral.blue)
        }
        // Light is unchanged: grey neutrals over a cool-leaning page, never warm.
        val light = GuideColors.Light
        val lightNeutrals =
            listOf(light.card, light.textPrimary, light.textSecondary, light.textSupporting, light.progressTrack)
        for (neutral in lightNeutrals) {
            val channels = listOf(neutral.red, neutral.green, neutral.blue)
            assertTrue("neutral $neutral is tinted", channels.max() - channels.min() <= NEUTRAL_SPREAD)
        }
        val spread = listOf(light.page.red, light.page.green, light.page.blue).let { it.max() - it.min() }
        assertTrue("page ${light.page} is tinted", spread <= PAGE_SPREAD)
        assertTrue("page ${light.page} is warm", light.page.blue >= light.page.red)
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
    fun theDarkPanelIsTheGuidesOwnTokens_andIsOneInstance() {
        val panel = GuideColors.Dark.panel
        assertEquals(Color(0xFF1C1B19), panel.ground)
        assertEquals(Color(0xFFF3EEE3), panel.text)
        assertEquals(Color(0xFFB3AB9D), panel.secondary)
        assertEquals(Color(0xFFE8642C), panel.ring)
        // One instance per theme, so the static local it feeds does not recompose its subtree on every read.
        assertTrue(GuideColors.Dark.panel === GuideColors.Dark.panel)
        assertTrue(GuideColors.Light.panel === GuideColors.Light.panel)
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
            // Light's does not reach the 4.5:1 text needs, so it must not be used for text. Dark's is the brand muted,
            // which does clear it, so only the 3:1 stroke floor above is asserted for Dark.
            if (c === GuideColors.Dark) continue
            val seen = c.textSupporting.compositeOver(c.page).luminance()
            val ratio = (maxOf(seen, c.page.luminance()) + 0.05) / (minOf(seen, c.page.luminance()) + 0.05)
            assertTrue("$name tertiary is $ratio: if it now clears 4.5, this guard can go", ratio < 4.5)
        }
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
        assertEquals(Color(0xFFCFCBC2), GuideColors.Dark.keyCapStroke)
        // Light's cap outline is its line.
        assertEquals(GuideColors.Light.line, GuideColors.Light.keyCapStroke)
    }

    @Test
    fun theNowLineIsFaintAndTheTimelineRulesAreFainter() {
        assertEquals(0.35f, GuideColors.Dark.nowRailAlpha)
        assertEquals(Color(0xFF3D3832), GuideColors.Dark.line)
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
    fun onlyLightTintsTheChannelMarks() {
        assertEquals(null, GuideColors.Dark.markTint)
        val ink = GuideColors.Light.markTint
        assertTrue("Light tints the mono marks", ink != null)
        assertEquals("the tint is opaque ink", 1f, ink!!.alpha, 0f)
        assertTrue("the ink is dark", ink.luminance() < 0.05f)
    }

    @Test
    fun onlyTheCellsReadTheStrokeOnlyTertiary() {
        // Only the cells (chevrons) read it: any other file that does is drawing text with it. Every module that draws
        // the guide's tokens is scanned, live TV's banner included.
        val features = File("../../feature").listFiles().orEmpty().map { File(it, "src/main/kotlin") }
        val roots = listOf(File("src/main/kotlin")) + features
        val readers =
            roots
                .filter { it.isDirectory }
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.extension == "kt" && it.name != "GuideColors.kt" }
                        .filter { "textSupporting" in it.readText() }
                        .map { it.name }
                        .toList()
                }.sorted()
        assertEquals(listOf("GuideCells.kt"), readers)
    }

    @Test
    fun theSheetColoursAreOnePerThemeAndReachThePanel() {
        assertEquals(Color(0xFF262320), GuideColors.Dark.sheet.surface)
        assertEquals(Color(0xFF3D3832), GuideColors.Dark.sheet.line)
        assertEquals(Color(0xFFFFD166), GuideColors.Dark.sheet.ratingBadge)
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            assertSame(c.sheet, c.panel.sheet)
            assertTrue("$name sheet is not the page", c.sheet.surface != c.page)
        }
    }

    @Test
    fun textAndTheFocusRingReadOnTheSheetInBothThemes() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            assertContrast(4.5, c.textPrimary, c.sheet.surface, "$name text on the sheet", c.page)
            assertContrast(4.5, c.textSecondary, c.sheet.surface, "$name secondary text on the sheet", c.page)
            assertContrast(4.5, c.sheet.ratingBadge, c.sheet.surface, "$name kids tag on the sheet", c.page)
            assertContrast(3.0, c.focusFill, c.sheet.surface, "$name focus ring on the sheet", c.page)
            assertContrast(4.5, c.focusText, c.focusFill, "$name focused menu row text on the fill", c.page)
        }
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
