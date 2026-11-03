// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.ui.beneathBuilder

/** #559: the list under the builder must not keep drawing its focus ring next to the builder's own. */
class ChannelsBeneathBuilderTest {
    private val listed = ChannelsUi.Empty.copy(focus = ChannelsFocus.New)

    @Test
    fun theListDrawsNoFocusWhileTheBuilderShows() {
        val shown = listed.copy(builder = BuilderMode.New).beneathBuilder()

        assertNull(shown.focus)
    }

    @Test
    fun theListKeepsItsFocusOtherwise() {
        assertEquals(ChannelsFocus.New, listed.beneathBuilder().focus)
    }
}
