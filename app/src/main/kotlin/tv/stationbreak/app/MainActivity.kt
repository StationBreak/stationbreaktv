// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tv.stationbreak.core.ui.StationBreakTheme

/** Launch activity. Shows the hello-world screen until the real TV UI (feature:live) replaces it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StationBreakTheme {
                HelloWorldScreen()
            }
        }
    }
}
