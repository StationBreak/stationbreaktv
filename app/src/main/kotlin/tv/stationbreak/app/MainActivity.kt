// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle

/** Placeholder launch activity. The real TV UI arrives with S0.2.2. */
class MainActivity : Activity() {
    // Deliberate failing PR (spec 01, 2.6): NotificationChannel is API 26; the Insignia is API 25.
    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannel("x", "x", NotificationManager.IMPORTANCE_LOW)
    }
}
