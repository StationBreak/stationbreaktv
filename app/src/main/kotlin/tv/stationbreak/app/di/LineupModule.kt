// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.app.lineupTimingProbeFor
import tv.stationbreak.app.standardOffsetMinutes
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.LineupRefresher
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.data.settings.lineup.FileLineupStore
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Clock

private const val TAG = "Lineup"

/**
 * The channel lineup (spec 12): its file store, the one service that owns it in memory, what features read it through,
 * and the refresher that keeps it current. The store is deliberately **not** given to the `SessionRepository` or any
 * `AccountCache`: signing out or a rejected session forgets the saved library, never the lineup (ruling 2).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LineupModule {
    @Provides
    @Singleton
    fun lineupStore(
        @ApplicationContext context: Context,
    ): LineupStore = FileLineupStore(context)

    // Events carry no ID, name or content, so their names are safe to log. The timings are numbers only. The probe is
    // debug builds only (null in release); it needs the schedule source, which needs this service, hence the Provider.
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // The probe's own scope: a root, with nothing to inject a dispatcher into.
    fun lineupService(
        store: LineupStore,
        library: LibraryRepository,
        schedule: Provider<ScheduleSource>,
    ): LineupService {
        val failed = CoroutineExceptionHandler { _, _ -> Log.w(TAG, "ProbeFailed") }
        val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failed)
        val probe = lineupTimingProbeFor(BuildConfig.DEBUG, probeScope, { schedule.get() }) { Log.i(TAG, it) }
        return LineupService(
            store,
            library,
            Clock.System,
            { standardOffsetMinutes() },
            { Log.i(TAG, it.name) },
            { ms ->
                Log.i(TAG, "RefreshTookMs=$ms")
                probe?.onRefreshed()
            },
        )
    }

    @Provides
    fun channelRepository(service: LineupService): ChannelRepository = service

    @Provides
    @Singleton
    fun scheduleSource(
        service: LineupService,
        library: LibraryRepository,
        settings: ServerSettings,
    ): ScheduleSource =
        // The timing is numbers only: the day's index and how long it took to build, never an ID or a title.
        BuiltInScheduleSource(
            service,
            library,
            timed = { day, ms -> Log.i(TAG, "NextDayBuild day=$day ms=$ms") },
        ) { settings.loadLibraries() }

    /** Follows the account the saved library holds and the libraries the user ticked, as the sync does. */
    @Provides
    @Singleton
    fun lineupRefresher(
        service: LineupService,
        library: LibraryRepository,
        settings: ServerSettings,
    ): LineupRefresher =
        LineupRefresher(service, library, { settings.loadLibraries() }) {
            Log.i(TAG, it.name)
        }
}
