// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.domain.kids.KidsIdleFallback
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenFits
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.ui.KEEP_ON_TAG
import tv.stationbreak.feature.live.ui.keepOnLogging
import javax.inject.Inject

/** Launch activity. Hosts the navigation graph, which opens on the server screen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Told of every key press, so a TV left alone for a while goes back to Kids Mode (#789). */
    @Inject
    lateinit var kidsIdle: KidsIdleFallback

    // Called for every key press (and touch) the activity is sent, before it is handled.
    override fun onUserInteraction() {
        super.onUserInteraction()
        kidsIdle.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleLine("onCreate restored=${savedInstanceState != null}")
        // Stan's opening shows for a new activity, not a restored one (see showsOpening).
        val coldLaunch =
            showsOpening(
                restored = savedInstanceState != null,
                skippable = BuildConfig.OPENING_SKIPPABLE,
                skipExtra = intent?.getBooleanExtra(EXTRA_SKIP_OPENING, false) == true,
            )
        setContent {
            // The viewer's screen fit (Settings, Appearance): live TV's picture and every screen lay out with it.
            val fit by ScreenFits.get(this).fit.collectAsState()
            CompositionLocalProvider(LocalScreenFit provides fit) {
                StationBreakTheme {
                    AppNavHost(coldLaunch)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleLine("onStart")
    }

    override fun onStop() {
        super.onStop()
        lifecycleLine("onStop")
    }

    // Diagnosing the screen-on flag on a device, under the same gate as the flag's own lines: a debuggable build, or
    // log.tag.SBLIVE set to DEBUG. Silent otherwise.
    private fun lifecycleLine(what: String) {
        if (keepOnLogging(this)) Log.i(KEEP_ON_TAG, "activity $what task=$taskId")
    }
}
