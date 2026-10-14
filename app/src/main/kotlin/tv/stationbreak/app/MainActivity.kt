// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.ui.StationBreakTheme

/** Launch activity. Hosts the navigation graph, which opens on the server screen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StationBreakTheme {
                AppNavHost()
            }
        }
    }
}
