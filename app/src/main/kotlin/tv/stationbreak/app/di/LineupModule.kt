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
import kotlinx.coroutines.launch
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.app.appBuildOf
import tv.stationbreak.app.daySnapshotLine
import tv.stationbreak.app.lineupTimingProbeFor
import tv.stationbreak.app.standardOffsetMinutes
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.domain.lineup.BuildProbes
import tv.stationbreak.core.domain.lineup.BuiltInChannelProperties
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelManager
import tv.stationbreak.core.domain.lineup.ChannelPropertiesReader
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.HouseholdSetting
import tv.stationbreak.core.domain.lineup.LineupEvent
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
import tv.stationbreak.core.domain.lineup.repair.LostRepairHook
import tv.stationbreak.core.domain.lineup.repair.Successors
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.savedSelectionOf
import tv.stationbreak.core.domain.selection.AddLandings
import tv.stationbreak.core.domain.selection.FirstSyncLibraries
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryPasses
import tv.stationbreak.core.domain.selection.LibrarySelection
import tv.stationbreak.core.domain.selection.LibrarySetup
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionConfig
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.domain.selection.SelectionResume
import tv.stationbreak.core.domain.selection.ServerListings
import tv.stationbreak.core.model.ItemId
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
            unknownSeries = { Log.i(TAG, "UnknownSeriesEpisodes=$it") },
            // What a save trimmed from the repair record to fit: counts only.
            trimmed = { fixes, covering, selection ->
                Log.i(TAG, "RepairsTrimmed fixes=$fixes coveringFixes=$covering selection=$selection")
            },
        )
    }

    @Provides
    fun channelRepository(service: LineupService): ChannelRepository = service

    /** The "Use my collections" flag, read by the Channels screen. */
    @Provides
    fun householdSetting(service: LineupService): HouseholdSetting = service

    /**
     * How features change the lineup (spec 21): through the service's lock, with the playable libraries the viewer
     * chose (spec 26: none while the selection is gone, never one being added).
     */
    @Provides
    @Singleton
    fun channelEditor(
        service: LineupService,
        settings: ServerSettings,
    ): ChannelEditor = ChannelManager(service) { settings.loadPlayableLibraries() }

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
        // The chosen libraries, not the playable ones: the picker takes null as "no filter", so a gone selection
        // (nothing playable) would list every library on the server.
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
     * What a channel's properties screen reads (#631): the lineup, the catalog and the schedule's hidden reason, with
     * the sources' words from the same lookups the builder's open uses.
     */
    @Provides
    @Singleton
    fun channelProperties(
        service: LineupService,
        library: LibraryRepository,
        schedule: ScheduleSource,
        sessions: SessionRepository,
        server: MediaServer,
    ): ChannelPropertiesReader {
        val labels = SourceLabels(library) { serverLibraries(sessions, server, it) }
        return BuiltInChannelProperties(service, library, schedule, labels::invoke, labels::studios)
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
    ): ChannelPreviews = BuiltInChannelPreviews(service, library, shared) { settings.loadPlayableLibraries() }

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
                        "diskMs=${it.diskMs} inputsMs=${it.inputsMs} reused=${it.reused} reuseMs=${it.reuseMs}",
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
            // One lock-free read of the stored selection (spec 26): the chosen libraries, their seq and reset seq, and
            // the playable ones. The source hands the seqs on to the service's prune and trim.
            saved = savedSelectionOf(settings),
        )

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

    /**
     * The repair engine (spec 26): one per process, as its calls take turns on one lock. Its successors are the
     * library's own conservative matches of an item that came back under a new id; its stored seq is the one the
     * settings keep after a sign-out, for a setup.
     */
    @Provides
    @Singleton
    fun todayRepairer(
        service: LineupService,
        source: BuiltInScheduleSource,
        library: LibraryRepository,
        settings: ServerSettings,
    ): TodayRepairer =
        TodayRepairer(service, source, library, Successors { a, l, g -> library.successors(a, l, g) }) {
            settings.loadSelectionSeq()
        }

    /**
     * The one writer of the library selection (spec 26, Part 4): onboarding's setup, the Settings changes, the landing
     * of an added library and the resume at start all go through it. Its events are names only, and the resume's time
     * a number. Its restart asks for the add pass ([LibraryPasses], in LibraryModule). It is bound under [AppStart]
     * only, as its resume opens the gate: everything else gets one of its narrow ports.
     */
    @Provides
    @Singleton
    @AppStart
    @Suppress("LongParameterList") // What the selection writes and asks, one for one.
    fun librarySelection(
        store: SelectionStore,
        library: LibraryRepository,
        server: MediaServer,
        sessions: SessionRepository,
        repairer: TodayRepairer,
        service: LineupService,
        source: BuiltInScheduleSource,
        passes: LibraryPasses,
        firstSync: FirstSync,
        stayCurrent: StayCurrentRequests,
        @AppStart gate: ResumeGate,
    ): LibrarySelection =
        LibrarySelection(
            store,
            library,
            server,
            { sessions.current.value },
            repairer,
            service,
            source,
            passes,
            firstSync,
            stayCurrent,
            gate,
            SelectionConfig(),
            Clock.System,
            // How long the resume at start took: a number only. Logging is best effort.
            { ms -> runCatching { Log.i(TAG, "SelectionResumed ms=$ms") } },
        ) { Log.i(TAG, it.name) }

    @Provides
    fun libraryChoices(
        @AppStart selection: LibrarySelection,
    ): LibraryChoices = selection

    @Provides
    fun librarySetup(
        @AppStart selection: LibrarySelection,
    ): LibrarySetup = selection

    /** The add pass's side of the selection (Task 4.4b). */
    @Provides
    fun addLandings(
        @AppStart selection: LibrarySelection,
    ): AddLandings = selection

    /** The first sync's library set, written under the selection's lock (Task 4.4b). */
    @Provides
    fun firstSyncLibraries(
        @AppStart selection: LibrarySelection,
    ): FirstSyncLibraries = selection

    /** Stay current's listings, followed by the selection (Task 4.5). */
    @Provides
    fun serverListings(
        @AppStart selection: LibrarySelection,
    ): ServerListings = selection

    /** The resume at start, which the app's start alone runs: qualified, so nothing else can inject it. */
    @Provides
    @AppStart
    fun selectionResume(
        @AppStart selection: LibrarySelection,
    ): SelectionResume = selection

    /**
     * The seam every selection operation and library worker waits on. It is its own object, opened by the selection's
     * resume and by the app's start whatever happened, so a selection that could not even be built never leaves
     * onboarding or the guide waiting.
     */
    @Provides
    @Singleton
    @AppStart
    fun resumeGate(): ResumeGate = ResumeGate()

    /** What features and workers wait on: the gate as a [SelectionReady] only, which cannot open it. */
    @Provides
    fun selectionReady(
        @AppStart gate: ResumeGate,
    ): SelectionReady = SelectionReady { gate.await() }

    /**
     * A stay-current run soon, for a library whose unproven add was set back to be read again (spec 26): the open
     * trigger's own entry, so its rules (not while a sync runs, not before the first sync ended) hold. The provider is
     * lazy: stay current's runner will follow the selection's listings (Task 4.5).
     *
     * `onOpen` drops a request that comes while a run is going (it queues nothing then). That loses nothing: the
     * set-back library carries a durable rewalk mark in the cache, and the next run that gets past the first-sync gate
     * (the next open, or the periodic run) reads it.
     */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // A root scope for the request, which only enqueues; nothing to inject one into.
    fun stayCurrentRequests(stayCurrent: Provider<StayCurrent>): StayCurrentRequests {
        val failed = CoroutineExceptionHandler { _, _ -> Log.w(TAG, "StayCurrentRequestFailed") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failed)
        return StayCurrentRequests { scope.launch { stayCurrent.get().onOpen() } }
    }

    /** Follows the account the saved library holds and the libraries the user ticked, as the sync does. */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // The lineup, the library, the sync signal, the repairer and the selection's seq.
    fun lineupRefresher(
        service: LineupService,
        library: LibraryRepository,
        settings: ServerSettings,
        sync: SyncActivity,
        repairer: TodayRepairer,
        @AppStart selection: LibrarySelection,
    ): LineupRefresher {
        val report: (LineupEvent) -> Unit = { Log.i(TAG, it.name) }
        // After a run that wrote the library, and once after the start check, the blocks whose item left it are
        // repaired for today (spec 26). The repairer reads the playable libraries itself, under its lock, from the
        // schedule source's saved selection.
        val now = { Clock.System.now().toEpochMilliseconds() }
        val afterSync = LostRepairHook(repairer::repairLost, now, report)
        // The playable libraries: none while the selection is gone, never one being added.
        val playable: suspend () -> Set<ItemId>? = { settings.loadPlayableLibraries() }
        // The selection's seq, published once each change is saved: a change of it is a trigger (spec 26).
        return LineupRefresher(
            service,
            library,
            playable,
            sync,
            selection.selectionChanges,
            afterSync = afterSync::invoke,
            report = report,
        )
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
