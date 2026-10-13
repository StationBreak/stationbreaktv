// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ChannelIdTest {
    @Test
    fun equalValuesAreTheSameChannel() {
        assertEquals(ChannelId("ch-1"), ChannelId("ch-1"))
        assertNotEquals(ChannelId("ch-1"), ChannelId("ch-2"))
    }

    @Test
    fun keepsItsValue() {
        assertEquals("ch-1", ChannelId("ch-1").value)
    }

    @Test
    fun rejectsABlankValue() {
        assertFailsWith<IllegalArgumentException> { ChannelId("") }
        assertFailsWith<IllegalArgumentException> { ChannelId("  ") }
    }
}
