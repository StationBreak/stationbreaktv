// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import android.util.Log
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
import tv.stationbreak.core.domain.lineup.ScheduleWarmer
import tv.stationbreak.core.playback.probe.DeviceCapabilitiesCache
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.FirstSyncRunnerProvider
import tv.stationbreak.data.library.StayCurrent
import tv.stationbreak.data.library.StayCurrentProvider

private const val LINEUP_TAG = "Lineup"
private const val SYNC_TAG = "Sync"

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

        fun scheduleWarmer(): ScheduleWarmer

        fun deviceCapabilities(): DeviceCapabilitiesCache
    }

    // One object for the whole process, so the open trigger, the on-open work and the periodic job share one gate. It
    // is built over the stores the first-sync runner reads, which keeps the graph the tests replace as it is.
    private val stayCurrent: StayCurrent by lazy { StayCurrent.create(this, firstSyncRunner()) { Log.i(SYNC_TAG, it) } }

    // Each time the app comes to the foreground (a launch, or returning from Home) keep the saved library current,
    // quietly (spec 06 Unit 4). Off the main thread; a configuration change does not fire it.
    @Suppress("InjectDispatcher") // The Application is the root: there is nothing to inject a dispatcher into.
    override fun onCreate() {
        super.onCreate()
        // The handler is the last resort for the lineup refresher, which reports its own failures: anything that still
        // escapes is logged by name and does not crash the process.
        val failed = lineupFailureHandler { Log.w(LINEUP_TAG, it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failed)
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundTrigger(scope) { stayCurrent.onOpen() })
        // Coming back from standby: the warmer's waits do not run then, so it looks again now.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            ForegroundTrigger(scope) {
                EntryPointAccessors
                    .fromApplication(this@StationBreakApp, FirstSyncEntryPoint::class.java)
                    .scheduleWarmer()
                    .nudge()
            },
        )
        // The channel lineup follows the saved library: at start, and after each sync (spec 12). It reports its own
        // failures, so nothing it does reaches this scope. Built off the main thread, like the rest of the graph.
        // A refresh is background work that takes seconds on a slow TV, so it runs with a concurrency of one: at most
        // one thread of the default pool at a time, not the whole pool. That limits how many cores it takes. It does
        // not lower the OS priority of the thread it runs on.
        val lineupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + failed)
        scope.launch {
            val graph = EntryPointAccessors.fromApplication(this@StationBreakApp, FirstSyncEntryPoint::class.java)
            graph.lineupRefresher().start(lineupScope)
            // The schedule's days are built ahead, as the lineup loads and refreshes.
            graph.scheduleWarmer().start(scope)
        }
        // The codec probe walks the device's decoder list and takes seconds on a slow TV: do it now, off the main
        // thread, so the first screen that plays finds it done (spec 13). It reports no failure of its own.
        scope.launch {
            EntryPointAccessors
                .fromApplication(this@StationBreakApp, FirstSyncEntryPoint::class.java)
                .deviceCapabilities()
                .get()
        }
    }

    override fun firstSyncRunner(): FirstSyncRunner =
        EntryPointAccessors.fromApplication(this, FirstSyncEntryPoint::class.java).firstSyncRunner()

    override fun stayCurrent(): StayCurrent = stayCurrent
}
