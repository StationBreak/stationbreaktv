// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.lineup.LineupRefresher
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.FirstSyncRunnerProvider
import tv.stationbreak.data.library.StayCurrent
import tv.stationbreak.data.library.StayCurrentProvider

/**
 * The Hilt root. The bindings live in `di/`; the server screen's ViewModel consumes `MediaServer` (F0.5).
 *
 * It also hands the sync workers what they run (spec 06, tasks 3.6 and 4.7). WorkManager builds a worker itself, from
 * its class name, so a worker cannot be injected; it asks the `Application` instead. The graph is built on first use,
 * so this is safe even if WorkManager starts a worker before `onCreate` has finished.
 */
@HiltAndroidApp
class StationBreakApp :
    Application(),
    FirstSyncRunnerProvider,
    StayCurrentProvider {
    /** What the sync workers reach the graph through. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface FirstSyncEntryPoint {
        fun firstSyncRunner(): FirstSyncRunner

        fun lineupRefresher(): LineupRefresher
    }

    // One object for the whole process, so the open trigger, the on-open work and the periodic job share one gate. It
    // is built over the stores the first-sync runner reads, which keeps the graph the tests replace as it is.
    private val stayCurrent: StayCurrent by lazy { StayCurrent.create(this, firstSyncRunner()) }

    // Each time the app comes to the foreground (a launch, or returning from Home) keep the saved library current,
    // quietly (spec 06 Unit 4). Off the main thread; a configuration change does not fire it.
    @Suppress("InjectDispatcher") // The Application is the root: there is nothing to inject a dispatcher into.
    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundTrigger(scope) { stayCurrent.onOpen() })
        // The channel lineup follows the saved library: at start, and after each sync (spec 12). It reports its own
        // failures, so nothing it does reaches this scope. Built off the main thread, like the rest of the graph.
        scope.launch {
            val graph = EntryPointAccessors.fromApplication(this@StationBreakApp, FirstSyncEntryPoint::class.java)
            graph.lineupRefresher().start(scope)
        }
    }

    override fun firstSyncRunner(): FirstSyncRunner =
        EntryPointAccessors.fromApplication(this, FirstSyncEntryPoint::class.java).firstSyncRunner()

    override fun stayCurrent(): StayCurrent = stayCurrent
}
