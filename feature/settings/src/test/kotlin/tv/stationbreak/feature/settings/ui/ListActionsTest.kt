// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.ui.RemoteKey

/** The Pro and Libraries screens' keys: both are a [ListActions], so this is their Back-finishes-once behaviour. */
class ListActionsTest {
    private val calls = mutableListOf<String>()
    private val actions =
        ListActions(
            move = { up -> calls += if (up) "up" else "down" },
            select = { calls += "select" },
            onDone = { calls += "done" },
        )

    @Test
    fun upDownAndSelectReachTheirHandlers() {
        actions.pad(RemoteKey.Up)
        actions.pad(RemoteKey.Down)
        actions.pad(RemoteKey.Select)
        assertEquals(listOf("up", "down", "select"), calls)
    }

    @Test
    fun backFinishesOnce_andALaterKeyIsIgnored() {
        actions.pad(RemoteKey.Back)
        actions.pad(RemoteKey.Back)
        actions.pad(RemoteKey.Down)
        actions.pad(RemoteKey.Select)
        assertEquals(listOf("done"), calls)
    }

    @Test
    fun leftAndRightDoNothing() {
        actions.pad(RemoteKey.Left)
        actions.pad(RemoteKey.Right)
        assertEquals(emptyList<String>(), calls)
    }
}
