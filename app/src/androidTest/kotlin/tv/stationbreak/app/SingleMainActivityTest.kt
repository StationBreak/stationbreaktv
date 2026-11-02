// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #529: whatever starts the app, a start while an instance exists brings that instance forward. An explicit start
 * (`am start -n`, no action or category) followed by the launcher's start used to stack a second MainActivity, and Back
 * on the top one restarted the first beneath it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SingleMainActivityTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun noInstanceIsLeftOver() {
        assertTrue("a MainActivity from another test is still alive", instances().isEmpty())
    }

    @After
    fun finishWhatIsLeft() {
        instances().forEach { activity -> instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun instances(): List<Activity> {
        var found = emptyList<Activity>()
        instrumentation.runOnMainSync {
            found =
                Stage
                    .values()
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                    .filterIsInstance<MainActivity>()
                    .filter { !it.isFinishing }
                    .distinct()
        }
        return found
    }

    private fun resumed(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
        }
        return found
    }

    // The API 25 emulator starts in portrait and rotates, which recreates the host (see
    // app/src/debug/AndroidManifest.xml), so the first RESUMED instance may be a short-lived one. Wait until the
    // same instance has stayed RESUMED for a settle period, and hand that one back.
    private fun awaitStableResumed(): MainActivity {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        var candidate: MainActivity? = null
        var since = 0L
        while (System.nanoTime() < deadline) {
            val now = resumed()
            if (now == null || now !== candidate) {
                candidate = now
                since = System.nanoTime()
            } else if (System.nanoTime() - since >= TimeUnit.MILLISECONDS.toNanos(SETTLE_MS)) {
                return now
            }
            Thread.sleep(POLL_MS)
        }
        error("MainActivity never stayed RESUMED")
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aLauncherStartAfterAnExplicitStartBringsTheFirstInstanceForward() {
        start(Intent().setClassName(context, MainActivity::class.java.name))
        val first = awaitStableResumed()
        val newIntents = CountDownLatch(1)
        instrumentation.runOnMainSync {
            first.addOnNewIntentListener { newIntents.countDown() }
        }

        start(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(context, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )

        assertTrue("the first instance should get onNewIntent", newIntents.await(TIMEOUT_S, TimeUnit.SECONDS))
        val after = instances()
        assertEquals("exactly one MainActivity", 1, after.size)
        assertSame("the first instance, not a second", first, after.single())
        assertFalse("the first instance is not recreated", first.isDestroyed)
    }

    private companion object {
        const val TIMEOUT_S = 10L
        const val POLL_MS = 100L
        const val SETTLE_MS = 2_000L
    }
}
