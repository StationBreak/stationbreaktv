// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Activity
import android.os.Bundle
import android.provider.Settings

/** Placeholder launch activity. The real TV UI arrives with S0.2.2. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    fun sumOfSeven(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int): Int = a + b + c + d + e + f + g

    fun deviceId(): String? = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
}
