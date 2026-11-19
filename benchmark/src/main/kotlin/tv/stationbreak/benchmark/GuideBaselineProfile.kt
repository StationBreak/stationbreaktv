// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.benchmark

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the Baseline Profile for the guide (#416, ADR-0008 decision 3): the scenarios of the ADR's device runs, so
 * the code a first guide open and a sweep through it use is compiled ahead of time, which the older TVs need most.
 *
 * Needs a device on Android 13 or later (Baseline Profile collection needs API 33, or root) with the target build
 * installed and **signed in with libraries chosen** (the first launch otherwise shows the sign-in screens, and the
 * profile records those instead). It sends keys the way a remote does and changes no device setting. The Gradle run
 * installs the app and test APKs, and by default uninstalls both afterwards, which would drop the sign-in: pass
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (docs/MODULES.md). `BaselineProfileRule` also stops
 * the app and resets its compiled state, for the app under test only.
 *
 * Every key press first checks that the app is still in front. If it crashed or was killed, the run fails at once
 * instead of sending the rest of its keys to the launcher.
 */
@RunWith(AndroidJUnit4::class)
class GuideBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun guide() =
        rule.collect(
            packageName = targetPackage(),
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            // Live TV tunes the last channel; give the first picture a moment before the guide opens over it.
            device.waitForIdle(Keys.SETTLE_MS)
            val keys = Keys(device, targetPackage())

            // Back from live TV opens the guide (feature:live OverlayKeys).
            keys.press(KeyEvent.KEYCODE_BACK)
            device.waitForIdle(Keys.SETTLE_MS)

            // s1: down stepping, one row per press.
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_DOWN, Keys.STEP_MS) }
            // s2: down held (repeat events 50 ms apart, the held-key path of the guide model).
            keys.hold(KeyEvent.KEYCODE_DPAD_DOWN, HELD_PRESSES)
            // s3: sweep right and back, stepping.
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_RIGHT, Keys.STEP_MS) }
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_LEFT, Keys.STEP_MS) }
            // s4: the sweep held, the weakest case of ADR-0008.
            keys.hold(KeyEvent.KEYCODE_DPAD_RIGHT, HELD_PRESSES)
            keys.hold(KeyEvent.KEYCODE_DPAD_LEFT, HELD_PRESSES)
            // s5: mixed.
            repeat(STEPS) {
                keys.press(KeyEvent.KEYCODE_DPAD_DOWN, MIXED_MS)
                keys.press(KeyEvent.KEYCODE_DPAD_RIGHT, MIXED_MS)
            }
            keys.hold(KeyEvent.KEYCODE_DPAD_UP, HELD_PRESSES)

            // Close the guide again so a later run starts the same way.
            keys.press(KeyEvent.KEYCODE_BACK)
            device.waitForIdle(Keys.SETTLE_MS)
        }

    private companion object {
        const val STEPS = 8
        const val HELD_PRESSES = 30
        const val MIXED_MS = 100L
    }
}
