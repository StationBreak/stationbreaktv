// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The owner's naming rule (#94): the server's product name is spelled in one resource, `server_product_name`, and the
 * strings that need it take it as `%1$s`. Everything else says "your server" or "your library". A resource check, so
 * the next string that names the product fails here.
 */
class NamingTest {
    private val strings: Map<String, String> =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

    @Test
    fun theProductNameIsOneStringAndNoOtherStringSpellsIt() {
        assertEquals("Jellyfin", strings["server_product_name"])
        val others = strings - "server_product_name"
        val spellsIt = others.filterValues { it.contains("jellyfin", ignoreCase = true) }
        assertEquals("These strings spell the product name: ${spellsIt.keys}", emptyMap<String, String>(), spellsIt)
    }

    @Test
    fun theStringsThatAskTheViewerToActOnTheServerTakeItAsTheFirstArgument() {
        val taking =
            listOf(
                "server_headline",
                "server_help",
                "server_connected_version",
                "welcome_body",
                "error_refused_message",
                "error_not_jellyfin_message",
                "error_not_jellyfin_details",
                "error_unsupported_version_message",
                "error_not_set_up_message",
                "libraries_none_message",
            )
        for (name in taking) {
            assertTrue("$name takes the product name as %1\$s", strings.getValue(name).contains("%1\$s"))
        }
    }

    @Test
    fun theHintsAndHelpThatDoNotNeedTheServerDoNotNameIt() {
        for (name in listOf("signin_username_hint", "signin_password_hint", "quick_connect_instructions")) {
            assertTrue("$name has no placeholder", !strings.getValue(name).contains("%"))
        }
    }
}
