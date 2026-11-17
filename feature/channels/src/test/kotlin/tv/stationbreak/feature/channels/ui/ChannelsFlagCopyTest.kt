// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** The group flags' copy ("ON · 48") and its spoken form: string resources with placeholders, never joined. */
class ChannelsFlagCopyTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String): String? {
        val found = Regex("""<string name="$name">(.*?)</string>""").find(xml)
        return found?.groupValues?.get(1)
    }

    private fun plural(
        name: String,
        quantity: String,
    ): String? =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.let { Regex("""<item quantity="$quantity">(.*?)</item>""").find(it)?.groupValues?.get(1) }

    /** What the screen shows: the template filled with the capitalised name and the count. */
    private fun flag(
        group: String,
        count: Int,
    ): String =
        checkNotNull(string("channels_group_flag"))
            .replace("%1\$s", checkNotNull(string(group)).uppercase())
            .replace("%2\$d", "$count")

    @Test
    fun theFlagIsTheNameInCapitalsAMiddleDotAndTheCount() {
        assertNotNull(string("channels_group_flag"))
        assertEquals("ON · 48", flag("channels_group_on", 48))
        assertEquals("MORE CHANNELS · 112", flag("channels_group_shelf", 112))
        assertEquals("OFF · 3", flag("channels_group_off", 3))
    }

    @Test
    fun theSpokenFormHasASingularAndAPlural() {
        assertEquals("%1\$s, %2\$d channel", plural("channels_group_spoken", "one"))
        assertEquals("%1\$s, %2\$d channels", plural("channels_group_spoken", "other"))
    }
}
