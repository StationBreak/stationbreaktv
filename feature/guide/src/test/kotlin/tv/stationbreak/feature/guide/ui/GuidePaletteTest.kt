// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(Color(0xFFF6F7F8), light.page)
        assertEquals(light.page, light.pageGradientStart)
        assertEquals(Color(0xFFB74719), light.focusFill)
        assertEquals(Color(0xFFFFFFFF), light.focusText)
        assertEquals(light.textPrimary, light.programmeText)
    }

    @Test
    fun noWarmTintsRemain() {
        // Every neutral role is grey: equal red, green and blue to within the 1-2 steps the near-black page keeps.
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            for (neutral in listOf(c.page, c.card, c.textPrimary, c.textSecondary, c.textSupporting, c.progressTrack)) {
                val channels = listOf(neutral.red, neutral.green, neutral.blue)
                val spread = channels.max() - channels.min()
                assertTrue("neutral $neutral is tinted", spread <= NEUTRAL_SPREAD)
            }
        }
    }

    @Test
    fun everyAuditedPairClearsItsMinimum() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            val card = c.card.compositeOver(c.page)
            assertContrast(3.0, c.focusFill, card, "$name focus on a card", c.page)
            assertContrast(3.0, c.focusFill, c.page, "$name focus on the page", c.page)
            assertContrast(4.5, c.focusText, c.focusFill, "$name focus text", c.page)
            assertContrast(4.5, c.textPrimary, c.page, "$name primary text", c.page)
            assertContrast(4.5, c.textSecondary, c.page, "$name secondary text", c.page)
            assertContrast(4.5, c.textSecondary, card, "$name secondary text on a card", c.page)
            assertContrast(4.5, c.programmeText, card, "$name programme text on a card", c.page)
            val key = c.keyHintPill.compositeOver(c.page)
            assertContrast(4.5, c.textSecondary, key, "$name secondary on a key", c.page)
            assertContrast(4.5, c.accent, c.page, "$name accent text", c.page)
            assertContrast(4.5, c.nowChipText, c.nowChipFill, "$name now chip", c.page)
            assertContrast(3.0, c.progressFill, c.progressTrack.compositeOver(c.page), "$name progress", c.page)
            val pill = c.menuPillSelected.compositeOver(c.page)
            assertContrast(3.0, c.menuPillFocused, pill, "$name focused menu", c.page)
            // Tertiary is for numbers, chevrons and the panel's channel line: 3:1, as large text.
            assertContrast(3.0, c.textSupporting, c.page, "$name tertiary text", c.page)
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
    fun theNowLineIsFaintAndTheTimelineRulesAreFainter() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            assertEquals(0.35f, c.nowRailAlpha)
            assertEquals(0.10f, c.line.alpha, 0.01f)
        }
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
