// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What `FirstSyncRoot` calls for each event: the cheer goes to `onCheered` only, never to `onFinished`, so the graph
 * holds the cheer's still over live TV only after a cheer.
 */
class FirstSyncEventRoutingTest {
    private fun routed(event: FirstSyncEvent): List<String> {
        val called = mutableListOf<String>()
        event.sendTo(
            onFinished = { called += "finished" },
            onSessionLost = { called += "sessionLost" },
            onCheered = { called += "cheered" },
        )
        return called
    }

    @Test
    fun theCheerGoesToOnCheeredAndNotToOnFinished() {
        assertEquals(listOf("cheered"), routed(FirstSyncEvent.Cheered))
    }

    @Test
    fun aSyncWithNothingToCheerGoesToOnFinished() {
        assertEquals(listOf("finished"), routed(FirstSyncEvent.Finished))
    }

    @Test
    fun aLostSessionGoesToOnSessionLost() {
        assertEquals(listOf("sessionLost"), routed(FirstSyncEvent.SessionLost))
    }
}
