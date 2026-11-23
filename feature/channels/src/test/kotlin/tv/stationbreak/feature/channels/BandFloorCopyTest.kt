// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.BandNeeded
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import java.io.File
import java.util.Locale

/** The owner's three lines for a number below a channel's band (#1097), as the screens format them. */
class BandFloorCopyTest {
    private val strings: Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun eachBandIsSaidByItsOwnMessage() {
        assertEquals(ChannelsMessage.NeedsFamily, belowBandMessage(BandNeeded.Family))
        assertEquals(ChannelsMessage.NeedsTeen, belowBandMessage(BandNeeded.Teen))
        assertEquals(ChannelsMessage.NeedsGeneral, belowBandMessage(BandNeeded.General))
    }

    @Test
    fun theThreeLinesReadAsApproved() {
        fun say(
            key: String,
            vararg args: Any,
        ) = String.format(Locale.ROOT, strings.getValue(key), *args)
        assertEquals(
            "Kids channels start at channel 34. Please use a channel in a higher tier.",
            say("channels_refused_needs_family", BandNeeded.Family.first),
        )
        assertEquals(
            "Teens channels start at channel 67. Please use a channel in a higher tier.",
            say("channels_refused_needs_teen", BandNeeded.Teen.first),
        )
        assertEquals(
            "Channels 1-99 are reserved for Family Mode stations. Please use a channel in a higher tier.",
            say("channels_refused_needs_general", ChannelNumbers.all.first, BandNeeded.General.first - 1),
        )
    }
}
