// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
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
import tv.stationbreak.app.appBuildOf
import tv.stationbreak.app.daySnapshotLine
import tv.stationbreak.app.lineupTimingProbeFor
import tv.stationbreak.app.standardOffsetMinutes
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.domain.lineup.BuildProbes
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelManager
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.LineupRefresher
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.PersistedDays
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWarmer
import tv.stationbreak.core.domain.lineup.SharedSnapshots
import tv.stationbreak.core.domain.lineup.SyncActivity
import tv.stationbreak.core.domain.lineup.builder.BuiltInChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.ChannelBuildManager
import tv.stationbreak.core.domain.lineup.builder.ChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.ChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.LibraryCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.NewSeed
import tv.stationbreak.core.domain.lineup.builder.SourceLabels
import tv.stationbreak.core.domain.lineup.builder.newCustomId
import tv.stationbreak.core.domain.lineup.eitherWriting
import tv.stationbreak.core.model.Library
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.StayCurrent
import tv.stationbreak.data.settings.lineup.FileDayStore
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
@Suppress("TooManyFunctions") // One provider for each thing the lineup is made of.
internal object LineupModule {
    @Provides
    @Singleton
    fun lineupStore(
        @ApplicationContext context: Context,
    ): LineupStore = FileLineupStore(context)

    /**
     * The built days on disk (#480). Unlike the lineup it is a cache of the library: it is an `AccountCache`, composed
     * with the library's in [LibraryModule], so sign-out and a switch of account clear it, and the lineup's own
     * `forget` clears it too.
     */
    @Provides
    @Singleton
    fun dayStore(
        @ApplicationContext context: Context,
    ): DayStore = FileDayStore(context)

    /**
     * The built days kept on disk, so that a new process starts from the day the last one built (#480). Keyed on this
     * install, so no other build of the app reads days this one made; disabled when the install cannot be told.
     */
    @Provides
    @Singleton
    fun persistedDays(
        @ApplicationContext context: Context,
        store: DayStore,
    ): PersistedDays {
        val app = appBuildOf(BuildConfig.VERSION_CODE, installTimeOf(context)) ?: return PersistedDays.disabled()
        return PersistedDays(store, app) { Log.i(TAG, daySnapshotLine(it)) }
    }

    // Events carry no ID, name or content, so their names are safe to log. The timings are numbers only. The probe is
    // debug builds only (null in release); it needs the schedule source, which needs this service, hence the Provider.
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // The probe's own scope: a root, with nothing to inject a dispatcher into.
    fun lineupService(
        store: LineupStore,
        library: LibraryRepository,
        days: DayStore,
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
            derived = days,
        )
    }

    @Provides
    fun channelRepository(service: LineupService): ChannelRepository = service

    /** How features change the lineup (spec 21): through the service's lock, with the libraries the viewer chose. */
    @Provides
    @Singleton
    fun channelEditor(
        service: LineupService,
        settings: ServerSettings,
    ): ChannelEditor = ChannelManager(service) { settings.loadLibraries() }

    /** The draw of a new channel's or a reshuffle's seed: 64 bits from the platform's secure random source. */
    @Provides
    @Singleton
    fun newSeed(): NewSeed = NewSeed.secure()

    /**
     * The server's libraries for [account], by the names the server gives them: the one existing current-state read
     * (ruling 13), through the signed-in session, and only when that session is [account]'s. Null when there is none
     * or the server can't be reached.
     */
    private suspend fun serverLibraries(
        sessions: SessionRepository,
        server: MediaServer,
        account: LibraryAccount,
    ): List<Library>? {
        val session = sessions.current.value ?: return null
        val ours = session.server.id == account.server && session.userId == account.user
        return if (ours) server.libraries(session).getOrNull() else null
    }

    /** What the builder's source pickers read (spec 22): the saved library, and the server's library names. */
    @Provides
    @Singleton
    fun catalogPicker(
        library: LibraryRepository,
        sessions: SessionRepository,
        server: MediaServer,
        settings: ServerSettings,
    ): CatalogPicker {
        val libraries: suspend (LibraryAccount) -> List<Library>? = { serverLibraries(sessions, server, it) }
        return LibraryCatalogPicker(library, libraries) { settings.loadLibraries() }
    }

    /** How features build channels (spec 22): through the service's lock, with the sources' words from the pickers. */
    @Provides
    @Singleton
    fun channelBuilder(
        service: LineupService,
        newSeed: NewSeed,
        library: LibraryRepository,
        sessions: SessionRepository,
        server: MediaServer,
    ): ChannelBuilder {
        val labels = SourceLabels(library) { serverLibraries(sessions, server, it) }
        return ChannelBuildManager(service, newSeed, { newCustomId() }, labels::invoke, labels::studios)
    }

    /**
     * The library snapshots the schedule source and the channel preview share (spec 22): a preview of a draft reads no
     * cut-off again that the lineup's days have read. Reads run in a scope of their own, not in a caller.
     */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // A root scope for the snapshot reads, with nothing to inject a dispatcher into.
    fun sharedSnapshots(): SharedSnapshots = SharedSnapshots(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** What a channel being built would air (spec 22): the real scheduler over the shared snapshots. */
    @Provides
    @Singleton
    fun channelPreviews(
        service: LineupService,
        library: LibraryRepository,
        shared: SharedSnapshots,
        settings: ServerSettings,
    ): ChannelPreviews = BuiltInChannelPreviews(service, library, shared) { settings.loadLibraries() }

    @Provides
    @Singleton
    fun builtInScheduleSource(
        persisted: PersistedDays,
        service: LineupService,
        library: LibraryRepository,
        settings: ServerSettings,
        shared: SharedSnapshots,
    ): BuiltInScheduleSource =
        // The timings are numbers only: day index, milliseconds and counts, never an ID or a title.
        BuiltInScheduleSource(
            service,
            library,
            timed = { day, ms -> Log.i(TAG, "NextDayBuild day=$day ms=$ms") },
            built = {
                Log.i(
                    TAG,
                    "DayBuild day=${it.day} ms=${it.ms} snapshotsMs=${it.snapshotsMs} poolsMs=${it.poolsMs} " +
                        "channels=${it.channels} poolsCpuMs=${it.poolsCpuMs} " +
                        "snapshotsProcCpuMs=${it.snapshotsProcCpuMs} " +
                        "poolsProcCpuMs=${it.poolsProcCpuMs} gcCount=${it.gcCount} gcBlockMs=${it.gcBlockMs} " +
                        "snapshot=${it.snapshot} evaluations=${it.evaluations} allocKb=${it.allocKb} " +
                        "diskMs=${it.diskMs} inputsMs=${it.inputsMs}",
                )
            },
            laidOut = { day, ms -> Log.i(TAG, "DayLayout day=$day ms=$ms") },
            probes =
                BuildProbes(
                    threadCpuNanos = { Debug.threadCpuTimeNanos() },
                    processCpuMs = { Process.getElapsedCpuTime() },
                    gcCount = { runtimeStat("art.gc.gc-count") },
                    gcBlockMs = { runtimeStat("art.gc.blocking-gc-time") },
                    allocatedBytes = { runtimeStat("art.gc.bytes-allocated") },
                ),
            persisted = persisted,
            shared = shared,
        ) { settings.loadLibraries() }

    @Provides
    fun scheduleSource(source: BuiltInScheduleSource): ScheduleSource = source

    /**
     * Builds the schedule's days ahead of the tune that needs them (#392). The builds are the source's own, at the
     * normal priority: a tune that meets one joins it, and must not wait on a slower thread than its own.
     */
    @Provides
    @Singleton
    fun scheduleWarmer(
        source: BuiltInScheduleSource,
        service: LineupService,
        library: LibraryRepository,
    ): ScheduleWarmer = ScheduleWarmer(source, service, library, Clock.System)

    /**
     * Whether a sync is writing the saved library (#434, #480): the first sync, or the quiet stay-current runs of the
     * process's one `StayCurrent`. One signal, so the refresher's one rule covers both. Both flows must be the
     * runners' own, the same instances the workers run: a copy would never turn on.
     */
    @Provides
    fun syncActivity(
        firstSync: FirstSyncRunner,
        stayCurrent: StayCurrent,
    ): SyncActivity = eitherWriting(firstSync.running, stayCurrent.runner.running)

    /** Follows the account the saved library holds and the libraries the user ticked, as the sync does. */
    @Provides
    @Singleton
    fun lineupRefresher(
        service: LineupService,
        library: LibraryRepository,
        settings: ServerSettings,
        sync: SyncActivity,
    ): LineupRefresher =
        LineupRefresher(service, library, { settings.loadLibraries() }, sync) {
            Log.i(TAG, it.name)
        }
}

/**
 * When this install of the app was made (`PackageInfo.lastUpdateTime`), or null when it cannot be told: then the days
 * are not kept on disk at all.
 */
@Suppress("TooGenericExceptionCaught") // Any failure to tell the install means no days on disk, never a crash.
private fun installTimeOf(context: Context): Long? =
    try {
        val packages = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") // The only form before API 33.
                packages.getPackageInfo(context.packageName, 0)
            }
        info.lastUpdateTime
    } catch (_: Exception) {
        null
    }

/** A number from ART's runtime statistics, or -1 where the runtime does not give it (or not as a number). */
private fun runtimeStat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1L
