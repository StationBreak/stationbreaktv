// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.model.ChannelId

/** A row carries its channel's logo key, so the list and the actions panel can draw the mark. */
class ChannelsMarksTest {
    private val a = ChannelId("ch-a")
    private val b = ChannelId("ch-b")

    @Test
    fun aRowTakesItsChannelsRecipeKey() {
        val channels =
            listOf(
                summary(a, 5).copy(recipeKey = "recipe-a"),
                summary(b, 6, kind = ChannelKind.Custom),
            )
        val rows = channelRows(channels, emptyMap()).associateBy { it.id }
        assertEquals("recipe-a", rows.getValue(a).markKey)
        assertNull("a custom channel has no logo, so it shows initials", rows.getValue(b).markKey)
    }

    @Test
    fun theKeyIsKeptWhateverTheGroup() {
        val hidden = summary(a, 5, ChannelStatus.UserHidden).copy(recipeKey = "recipe-a")
        assertEquals("recipe-a", channelRows(listOf(hidden), emptyMap()).single().markKey)
    }
}
