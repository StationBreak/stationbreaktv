// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.ComponentCallbacks2
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 24: the system's memory trims reach the player for one live visit, from the running level up, and stop when the
 * period is left.
 */
class LiveTrimTest {
    private class FakeHost : TrimHost {
        var registered: ComponentCallbacks2? = null
        var registrations = 0
        var unregistrations = 0

        override fun register(callbacks: ComponentCallbacks2) {
            registered = callbacks
            registrations++
        }

        override fun unregister(callbacks: ComponentCallbacks2) {
            if (registered === callbacks) registered = null
            unregistrations++
        }
    }

    @Test
    fun levelsFromRunningModerateUpReachThePlayerOnce() {
        val player = RecordingPlayer()
        val trim = LiveTrim(player)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(listOf("trim:5", "trim:80"), player.log)
    }

    @Test
    fun levelsBelowRunningModerateAreNotForwarded() {
        val player = RecordingPlayer()
        val trim = LiveTrim(player)
        trim.onTrimMemory(0)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE - 1)
        assertEquals(emptyList<String>(), player.log)
    }

    @Test
    fun theVisitRegistersOnceAndLeavingUnregistersAndStopsTheForwarding() {
        val inner = RecordingPlayer()
        val host = FakeHost()
        val period = LivePeriod(inner, Job(), null, host) {}
        val callbacks = host.registered
        assertEquals(1, host.registrations)
        host.registered!!.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(listOf("trim:10"), inner.log)

        period.leave()
        assertNull(host.registered)
        assertEquals(1, host.unregistrations)
        val afterLeave = inner.log.toList()
        callbacks!!.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(afterLeave, inner.log)
        // A second leave unregisters nothing more.
        period.leave()
        assertEquals(1, host.unregistrations)
    }

    @Test
    fun releasingThePlayerAlsoUnregisters() {
        val host = FakeHost()
        val period = LivePeriod(RecordingPlayer(), Job(), null, host) {}
        period.player.release()
        assertNull(host.registered)
        assertSame(1, host.unregistrations)
    }
}
