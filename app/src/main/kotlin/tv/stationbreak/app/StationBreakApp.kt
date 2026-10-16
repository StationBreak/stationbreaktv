// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.FirstSyncRunnerProvider

/**
 * The Hilt root. The bindings live in `di/`; the server screen's ViewModel consumes `MediaServer` (F0.5).
 *
 * It also hands the first-sync worker what it runs (spec 06, task 3.6). WorkManager builds the worker itself, from its
 * class name, so the worker cannot be injected; it asks the `Application` instead. The graph is built on first use, so
 * this is safe even if WorkManager starts the worker before `onCreate` has finished.
 */
@HiltAndroidApp
class StationBreakApp :
    Application(),
    FirstSyncRunnerProvider {
    /** What the worker reaches the graph through. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface FirstSyncEntryPoint {
        fun firstSyncRunner(): FirstSyncRunner
    }

    override fun firstSyncRunner(): FirstSyncRunner =
        EntryPointAccessors.fromApplication(this, FirstSyncEntryPoint::class.java).firstSyncRunner()
}
