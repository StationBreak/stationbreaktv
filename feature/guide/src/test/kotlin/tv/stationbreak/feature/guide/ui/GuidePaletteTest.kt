// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guide A colours, and the contrast pairs from the Guide A audit. */
class GuidePaletteTest {
    @Test
    fun darkAndLightUseTheGuideATokens() {
        val dark = GuideColors.Dark
        assertEquals(Color(0xFF191A19), dark.page)
        assertEquals(Color(0xFF212420), dark.pageGradientStart)
        assertEquals(Color(0xFF33352F), dark.card)
        assertEquals(Color(0xFF252725), dark.cardAlternate)
        assertEquals(Color(0xFFE8642C), dark.focusFill)
        assertEquals(Color(0xFF1C1B19), dark.focusText)
        assertEquals(Color(0xFF3E3C36), dark.progressTrack)
        assertEquals(Color(0xFFF3EEE3), dark.textPrimary)
        assertEquals(Color(0xFFB3B4AE), dark.textSecondary)

        val light = GuideColors.Light
        assertEquals(Color(0xFFF3EEE3), light.page)
        assertEquals(Color(0xFFF8F4EC), light.pageGradientStart)
        assertEquals(Color(0xFFFFFCF6), light.card)
        assertEquals(Color(0xFFFAF7F0), light.cardAlternate)
        assertEquals(Color(0xFFB74719), light.focusFill)
        assertEquals(Color(0xFFFFFFFF), light.focusText)
        assertEquals(Color(0xFFD6CEC0), light.progressTrack)
        assertEquals(Color(0xFF1C1B19), light.textPrimary)
        assertEquals(Color(0xFF625D54), light.textSecondary)
    }

    @Test
    fun everyAuditedPairClearsItsMinimum() {
        val dark = GuideColors.Dark
        val light = GuideColors.Light
        assertContrast(3.0, dark.focusFill, dark.card, "dark focus on a card")
        assertContrast(3.0, dark.focusFill, dark.cardAlternate, "dark focus on an alternate card")
        assertContrast(3.0, dark.focusFill, dark.pageGradientStart, "dark focus on the page")
        assertContrast(4.5, dark.focusText, dark.focusFill, "dark focus text")
        assertContrast(4.5, dark.textPrimary, dark.pageGradientStart, "dark primary text")
        assertContrast(4.5, dark.textSecondary, dark.pageGradientStart, "dark secondary text")
        assertContrast(4.5, dark.textSecondary, dark.keyHintPill, "dark secondary text on a key pill")
        assertContrast(4.5, dark.textSecondary, dark.card, "dark secondary text on a card")
        assertContrast(4.5, dark.programmeText, dark.card, "dark programme text")
        assertContrast(4.5, dark.textSupporting, dark.pageGradientStart, "dark supporting text")
        assertContrast(4.5, dark.accent, dark.pageGradientStart, "dark accent text")
        assertContrast(4.5, dark.nowChipText, dark.nowChipFill, "dark now chip")
        assertContrast(3.0, dark.progressFill, dark.progressTrack, "dark progress")
        assertContrast(3.0, dark.menuPillFocused, dark.menuPillSelected, "dark focused menu")

        assertContrast(3.0, light.focusFill, light.card, "light focus on a card")
        assertContrast(3.0, light.focusFill, light.cardAlternate, "light focus on an alternate card")
        assertContrast(3.0, light.focusFill, light.page, "light focus on the page")
        assertContrast(4.5, light.focusText, light.focusFill, "light focus text")
        assertContrast(4.5, light.textPrimary, light.page, "light primary text")
        assertContrast(4.5, light.textSecondary, light.page, "light secondary text")
        assertContrast(4.5, light.textSecondary, light.keyHintPill, "light secondary text on a key pill")
        assertContrast(4.5, light.textSecondary, light.cardAlternate, "light secondary text on a card")
        assertContrast(4.5, light.programmeText, light.cardAlternate, "light programme text")
        assertContrast(4.5, light.textSupporting, light.page, "light supporting text")
        assertContrast(4.5, light.accent, light.page, "light accent text")
        assertContrast(4.5, light.nowChipText, light.nowChipFill, "light now chip")
        assertContrast(3.0, light.progressFill, light.progressTrack, "light progress")
        assertContrast(3.0, light.menuPillFocused, light.menuPillSelected, "light focused menu")
    }

    @Test
    fun theLayersAndPlatesKeepTheirContrast() {
        for ((name, c) in listOf("dark" to GuideColors.Dark, "light" to GuideColors.Light)) {
            assertContrast(4.5, c.plateNumber, c.plateBottom, "$name plate number")
            assertContrast(4.5, c.plateName, c.plateBottom, "$name plate name")
            assertContrast(4.5, c.programmeText, c.card, "$name programme text on a card")
            assertContrast(4.5, c.textSecondary, c.card, "$name secondary text on a card")
            assertContrast(4.5, c.focusText, c.focusFill, "$name focus text")
            for (plate in listOf(c.plateTop, c.plateBottom, c.plateHighlight, c.plateShadow)) {
                assertEquals("$name plate colours are opaque", 1f, plate.alpha)
            }
        }
    }

    @Test
    fun eachLayerIsToldApartFromTheNext() {
        val dark = GuideColors.Dark
        val light = GuideColors.Light
        // Dark steps lighter all the way: page, panel, band, card.
        val darkLayers = listOf(dark.page, dark.gridPanel, dark.rowBand, dark.card)
        assertEquals(4, darkLayers.toSet().size)
        darkLayers.zipWithNext().forEach { (below, above) ->
            assertTrue("dark layers step lighter", above.luminance() > below.luminance())
            assertContrast(MIN_LAYER_STEP, above, below, "dark layer step")
        }
        // Light steps darker from the page to the panel and the band; the card is the lightest, a raised sheet.
        val lightLayers = listOf(light.page, light.gridPanel, light.rowBand, light.card)
        assertEquals(4, lightLayers.toSet().size)
        assertTrue("light panel is darker than the page", light.gridPanel.luminance() < light.page.luminance())
        assertTrue("light band is darker than the panel", light.rowBand.luminance() < light.gridPanel.luminance())
        assertTrue("light card is lighter than the band", light.card.luminance() > light.rowBand.luminance())
        lightLayers.zipWithNext().forEach { (a, b) -> assertContrast(MIN_LAYER_STEP, a, b, "light layer step") }
    }

    @Test
    fun theThemesAreOpaqueExceptWhereAllowed() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            listOf(c.gridPanel, c.rowBand, c.card, c.plateNumber, c.plateName).forEach { assertEquals(1f, it.alpha) }
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
    fun focusExtendsInTheDrawAndDoesNotScale() {
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

    private fun assertContrast(
        minimum: Double,
        a: Color,
        b: Color,
        what: String,
    ) {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        val ratio = (light + 0.05) / (dark + 0.05)
        assertTrue("$what is $ratio, needs $minimum", ratio >= minimum)
    }
}

private const val MIN_LAYER_STEP = 1.05
