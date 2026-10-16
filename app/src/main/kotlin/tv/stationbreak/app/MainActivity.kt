// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.data.library.StayCurrentProvider

/** Launch activity. Hosts the navigation graph, which opens on the server screen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh open (not a rotation or a restore): keep the saved library current, quietly (spec 06, Unit 4).
        if (savedInstanceState == null) {
            val stayCurrent = (application as? StayCurrentProvider)?.stayCurrent()
            if (stayCurrent != null) lifecycleScope.launch { stayCurrent.onOpen() }
        }
        setContent {
            StationBreakTheme {
                AppNavHost()
            }
        }
    }
}
