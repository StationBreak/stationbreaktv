// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 06 task 4.0, review round (Carrot H1): an "open" is the app coming to the foreground, not only a fresh launch.
 * The trigger watches the process lifecycle (ProcessLifecycleOwner in the app), which starts when the first activity
 * does and stops only after the last one has been gone for a moment, so returning from Home counts and a configuration
 * change (the activity is recreated, the process stays started) does not.
 */
class ForegroundTriggerTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var opens = 0
    private val process = Owner()

    init {
        process.registry.addObserver(ForegroundTrigger(scope) { opens++ })
    }

    @After
    fun stop() = scope.cancel()

    private fun foreground() {
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    private fun background() {
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    @Test
    fun theAppComingToTheForegroundTriggersAnOpen() {
        foreground()

        assertEquals(1, opens)
    }

    @Test
    fun returningFromHomeTriggersAnotherOpen() {
        foreground()
        background()
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertEquals(2, opens)
    }

    @Test
    fun resumingAndPausingInsideTheForegroundDoNotTrigger() {
        foreground()
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(1, opens)
    }

    @Test
    fun aConfigurationChangeDoesNotTrigger() {
        foreground()
        // A rotation destroys and recreates the activity, but the process lifecycle is untouched.
        val activity = Owner()
        activity.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        activity.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        activity.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        activity.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        val recreated = Owner()
        recreated.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        recreated.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertEquals(1, opens)
    }

    @Test
    fun anActionThatThrowsNeverReachesTheLifecycle() {
        val thrower = Owner()
        thrower.registry.addObserver(ForegroundTrigger(scope) { error("the open failed") })
        thrower.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        thrower.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        // Reaching here without a crash is the point: the trigger swallows what an open throws.
        assertEquals(0, opens)
    }
}
