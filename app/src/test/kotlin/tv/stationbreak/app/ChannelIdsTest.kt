// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ChannelId

class ChannelIdsTest {
    @Test
    fun parsesAChannelId() {
        assertEquals(ChannelId("ch-1"), parseChannelId("ch-1"))
    }

    @Test
    fun missingOrBlankIsNoChannel() {
        assertNull(parseChannelId(null))
        assertNull(parseChannelId(""))
        assertNull(parseChannelId("  "))
    }
}
