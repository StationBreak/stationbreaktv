// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.benchmark

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
            device.waitForIdle(SETTLE_MS)
            val keys = Keys(device, targetPackage())

            // Back from live TV opens the guide (feature:live OverlayKeys).
            keys.press(KeyEvent.KEYCODE_BACK)
            device.waitForIdle(SETTLE_MS)

            // s1: down stepping, one row per press.
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_DOWN, STEP_MS) }
            // s2: down held (repeat events 50 ms apart, the held-key path of the guide model).
            keys.hold(KeyEvent.KEYCODE_DPAD_DOWN, HELD_PRESSES)
            // s3: sweep right and back, stepping.
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_RIGHT, STEP_MS) }
            repeat(STEPS) { keys.press(KeyEvent.KEYCODE_DPAD_LEFT, STEP_MS) }
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
            device.waitForIdle(SETTLE_MS)
        }

    private fun targetPackage(): String {
        val arguments = InstrumentationRegistry.getArguments()
        return arguments.getString("targetAppId") ?: error("targetAppId is not set")
    }

    /** Remote keys by the input system: one press, or a run of presses spaced like a held key's repeats. */
    private class Keys(
        private val device: UiDevice,
        private val packageName: String,
    ) {
        fun press(
            keyCode: Int,
            thenWaitMs: Long = STEP_MS,
        ) {
            check(device.currentPackageName == packageName) {
                "$packageName is not in front (${device.currentPackageName}): the run stops before more keys go to it"
            }
            device.pressKeyCode(keyCode)
            Thread.sleep(thenWaitMs)
        }

        fun hold(
            keyCode: Int,
            presses: Int,
        ) {
            repeat(presses) { press(keyCode, HELD_MS) }
            Thread.sleep(STEP_MS)
        }
    }

    private companion object {
        const val SETTLE_MS = 3_000L
        const val STEPS = 8
        const val STEP_MS = 150L
        const val HELD_MS = 50L
        const val HELD_PRESSES = 30
        const val MIXED_MS = 100L
    }
}
