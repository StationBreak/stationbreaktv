// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** The permanent heads-up's copy (#785): in the string resources, factual, and with no number or threshold. */
class ChannelsHeadsUpCopyTest {
    private val copy: String? =
        Regex("""<string name="channels_heads_up">(.*?)</string>""")
            .find(File("src/main/res/values/strings.xml").readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun theCopyIsAStringResource() {
        assertNotNull("channels_heads_up is missing from strings.xml", copy)
        assertEquals("The more channels you switch on, the longer the guide takes to get ready.", copy)
    }

    @Test
    fun theCopyNamesNoNumberAndNoThreshold() {
        assertFalse(copy.orEmpty().any { it.isDigit() })
    }
}
