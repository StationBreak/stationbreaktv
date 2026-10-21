// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

/**
 * The exported [PlaybackTestActivity] runs only while USB/network debugging is on (`Settings.Global.ADB_ENABLED` is 1).
 * With debugging off, nothing on the device can have started it by `adb`, so any other app that tries is refused. A
 * pure function so it is unit-tested without Android.
 */
object AdbGate {
    fun allows(adbEnabled: Int): Boolean = adbEnabled == 1
}
