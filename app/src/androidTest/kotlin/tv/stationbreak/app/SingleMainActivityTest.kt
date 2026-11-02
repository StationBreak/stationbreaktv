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

    private fun awaitResumedFirst(): MainActivity {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        while (System.nanoTime() < deadline) {
            var resumed: MainActivity? = null
            instrumentation.runOnMainSync {
                resumed =
                    ActivityLifecycleMonitorRegistry
                        .getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .firstOrNull()
            }
            resumed?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("MainActivity never reached RESUMED")
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aLauncherStartAfterAnExplicitStartBringsTheFirstInstanceForward() {
        start(Intent().setClassName(context, MainActivity::class.java.name))
        val first = awaitResumedFirst()
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
    }
}
