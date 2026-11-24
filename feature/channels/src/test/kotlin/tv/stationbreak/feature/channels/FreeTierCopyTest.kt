// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FreeTier
import java.io.File
import java.util.Locale

/**
 * "Coming soon" is never said, and Pro is named only where a free viewer reaches for something that is part of it
 * (#99): the notes and the one button of the Pro prompt. Nothing else in the module names Pro.
 *
 * #1071 supersedes the 2026-11-19 rule (#978) that a free viewer never sees Pro: a gate that refuses silently reads
 * as a bug, so every Pro gate says it is Pro, with or without a store. The exact keys below are the only strings that
 * may say so.
 */
class FreeTierCopyTest {
    private val strings: Map<String, String> =
        checkNotNull(File("src/main/res/values").listFiles { f -> f.extension == "xml" })
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(file.readText()).toList()
            }.associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    // The strings a free viewer reads on reaching for a Pro action (#1071): the notes, the builder's own lines and the
    // button to the purchase screen. They are the only exemption. Exact keys, all in strings_pro.xml, so any other Pro
    // mention fails.
    private val proPrompt =
        setOf(
            "pro_custom_limit_note",
            "channels_pro_needs_reshape",
            "channels_pro_needs_reset",
            "channels_pro_needs_adult_copy",
            "builder_pro_needs",
            "channels_action_see_pro",
        )

    @Test
    fun noStringButThePromptNamesProOrComingSoon() {
        assertTrue("the strings were not found", strings.size > 50)
        (strings - proPrompt).forEach { (name, text) ->
            assertFalse("$name: $text", Regex("""\bPro\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("$name: $text", text.contains("coming soon", ignoreCase = true))
        }
    }

    @Test
    fun theExemptKeysAreExactlyTheProNamingStringsOfStringsProXml() {
        val file = File("src/main/res/values/strings_pro.xml").readText()
        val named =
            Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
                .findAll(file)
                .filter { Regex("""\bPro\b""").containsMatchIn(it.groupValues[2]) }
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(proPrompt, named)
    }

    @Test
    fun theProPromptNamesProAndOffersTheWay() {
        proPrompt.forEach { assertTrue(it, strings.getValue(it).contains("Pro")) }
        assertFalse(strings.values.any { it.contains("coming soon", ignoreCase = true) })
    }

    // The copy rule (#1076): a note shows alone on its screen, so it is the first mention and says StationBreak Pro;
    // See Pro sits beside a note that already said it, so it stays short.
    @Test
    fun everyNoteSaysStationBreakPro_andTheButtonBesideItSaysPro() {
        (proPrompt - "channels_action_see_pro").forEach {
            assertTrue(it, strings.getValue(it).contains("StationBreak Pro"))
            assertFalse(it, Regex("""(?<!StationBreak )\bPro\b""").containsMatchIn(strings.getValue(it)))
        }
        assertEquals("See Pro", strings["channels_action_see_pro"])
    }

    @Test
    fun theFreeShelfLinesDoNotOfferToSwitchOn() {
        assertEquals("Fits, but your lineup is full", strings["channels_shelf_below_cap_free"])
        assertEquals("Adult content. Can't be switched on", strings["channels_shelf_opt_in_free"])
        assertTrue(strings.getValue("channels_shelf_below_cap").contains("Switch it on"))
    }

    @Test
    fun theCustomLimitNoteSaysWhatFreeHoldsAndThatMoreIsPro() {
        val note =
            "Free includes 5 custom channels. Delete some of yours to make room. More is part of StationBreak Pro."
        // The allowance is a format argument, read from the constant that enforces it.
        val format = strings.getValue("pro_custom_limit_note")
        assertEquals(note, String.format(Locale.ROOT, format, FreeTier.CUSTOM_CHANNELS))
        assertEquals(5, FreeTier.CUSTOM_CHANNELS)
    }
}
