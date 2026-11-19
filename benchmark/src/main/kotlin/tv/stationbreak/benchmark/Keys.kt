// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** The application ID of the build under test, passed by the Gradle run as the `targetAppId` argument. */
internal fun targetPackage(): String {
    val id = InstrumentationRegistry.getArguments().getString("targetAppId")
    return id ?: error("targetAppId is not set")
}

/**
 * Remote keys by the input system: one press, or a run of presses spaced like a held key's repeats. Every press first
 * checks that the app is still in front, so a crash or kill fails the run at once instead of sending the rest of its
 * keys to the launcher.
 */
internal class Keys(
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

    companion object {
        const val SETTLE_MS = 3_000L
        const val STEP_MS = 150L
        const val HELD_MS = 50L
    }
}
