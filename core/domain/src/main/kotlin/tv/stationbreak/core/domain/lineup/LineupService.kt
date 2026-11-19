// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.domain.lineup.repair.RepairConfig
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.Trimmed
import tv.stationbreak.core.domain.lineup.repair.fitted
import tv.stationbreak.core.domain.lineup.repair.trimmedToFit
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.KeptReasonsMemo
import tv.stationbreak.core.engine.lineup.LeftoversRecipe
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.engine.lineup.withPendingBroughtForward
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.scheduler.Epochs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A lineup with the revision it was published at, and the refresher's [meta] about it. A new refresh gives a new
 * revision, so it is a cache key; a change of [meta] alone keeps the revision, since no channel changed. [writable]
 * is whether this session may write the account's file, as it stood when this was published (spec 21). [repairs] is the
 * per-TV repair record (spec 26) that is saved with the lineup; a change of it alone keeps the revision too.
 * [firstChoiceMade] is whether the planner has made the lineup's first choice ([FirstLineup]).
 */
internal data class Revisioned(
    val revision: Long,
    val lineup: Lineup,
    val meta: RefreshMeta = RefreshMeta(),
    val repairs: TodayRepairs = TodayRepairs.NONE,
    val writable: Boolean = false,
) {
    val firstChoiceMade: Boolean get() = firstChoiceMade(lineup, meta)
}

/**
 * The one owner of each account's channel lineup in memory (spec 12): it loads, plans and saves it, and everything
 * else reads it from here ([lineup], [channels]). Nothing else decodes the store.
 *
 * A lineup is per account, by the canonical forms of its server and user IDs (`CanonicalId.parseOrNull`), so an
 * upper-case or dashed spelling is the same account. An account whose IDs are not IDs at all has no lineup: it is
 * reported ([LineupEvent.NonCanonicalAccount]), never thrown, and the store is never asked. The library is read with
 * the account as given, since that is how it was synced.
 *
 * Stored data is never destroyed. Every load-plan-save of an account runs under one lock of that account:
 * - nothing stored: plan from scratch (or from this session's lineup, if a save failed earlier) and save;
 * - a lineup: plan from it and save;
 * - a lineup from a newer app, or a file that could not be read just now: plan in memory for this session only, from
 *   this session's lineup if there is one, and never save or set it aside;
 * - an unreadable file (or another account's lineup): set it aside first, then plan from scratch and save. If it
 *   cannot be set aside it is treated like a newer one: never written over.
 *
 * Planning reads the library at now (a planning read, `LibraryRepository.ruleSnapshot`) and the current collection
 * names, and takes the device zone's standard offset from [standardOffsetMinutes] only for a lineup not yet finalised.
 */
@Suppress("TooManyFunctions", "LargeClass") // The one owner of the lineup: load, plan and save stay together.
class LineupService internal constructor(
    private val store: LineupStore,
    private val library: LibraryRepository,
    private val clock: Clock,
    private val standardOffsetMinutes: () -> Int,
    private val report: (LineupEvent) -> Unit,
    private val seams: LineupSeams,
) : ChannelRepository,
    HouseholdSetting,
    CollectionsAtSetup,
    FirstLineup {
    /** For tests: a small catalog in place of the real one. */
    internal constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
        catalog: StarterCatalogView,
    ) : this(store, library, clock, standardOffsetMinutes, report, LineupSeams(catalog))

    constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
    ) : this(store, library, clock, standardOffsetMinutes, report, LineupSeams())

    /**
     * As above, and [timed] is told each completed refresh's wall time in milliseconds (a number, nothing else).
     * [unknownSeries] is told, at each planning read, how many live episodes name a series the saved library does not
     * hold (a count, nothing else): a gap there is a show whose episodes cannot be named or homed (issue 604).
     * [derived] is told when a lineup is forgotten ([forget]): what was built from it (the days on disk, #480) goes
     * too, by the store's `forget`; a rebuild or reset only deletes those day files and never forgets the account.
     * [trimmed] is told what a lineup save trimmed from the repair record to fit (three counts: fixes, fixes
     * covering now, selection repairs; nothing else). [rulesFormats] is told, once, at the first read of the stored
     * lineup on any path, how many rules files it holds at each rules format version and how many the read upgraded
     * (counts only, ADR-0026).
     */
    @Suppress("LongParameterList") // The app's one constructor: the store, the reads and six numbers-only hooks.
    constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
        timed: (Long) -> Unit,
        derived: DayStore? = null,
        unknownSeries: (Int) -> Unit = {},
        trimmed: (fixes: Int, coveringFixes: Int, selection: Int) -> Unit = { _, _, _ -> },
        rulesFormats: (RulesFormats) -> Unit = {},
    ) : this(
        store,
        library,
        clock,
        standardOffsetMinutes,
        report,
        LineupSeams(
            timed = timed,
            derived = derived,
            unknownSeries = unknownSeries,
            trimmed = trimmed,
            rulesFormats = rulesFormats,
        ),
    )

    internal val catalog: StarterCatalogView get() = seams.catalog
    private val days = LineupDays(seams.catalog)

    // Background rolls (see [rollSoon]): their own scope, so a roll outlives the read that asked for it.
    private val rolls = BackgroundRolls(seams.rollDispatcher)

    // The one-time rewrite of rules a load upgraded (see [rewriteSoon]): in the background, like the rolls.
    private val rewrites = BackgroundRolls(seams.rollDispatcher)

    // The play keys of the last few revisions and days ([PlayKeyCache]).
    private val playKeys = PlayKeyCache()

    private class Entry {
        val mutex = Mutex()
        val state = MutableStateFlow<Revisioned?>(null)

        /** The repair record of [state], for readers that want nothing else. */
        val repairs = MutableStateFlow(TodayRepairs.NONE)

        /** The one way to change [state]: it keeps [repairs] in step. */
        fun set(value: Revisioned?) {
            state.value = value
            repairs.value = value?.repairs ?: TodayRepairs.NONE
        }

        /** True once the store was read (successfully) for this account. Written under [mutex]. */
        @Volatile var loaded = false

        /**
         * Whether this session may write the account's file. False when the session started from a file that was
         * unreadable just now, from a newer app, or unreadable and could not be set aside. Set under [mutex] at the
         * start of each refresh, **every save path must check it**, and it is never true while a stored file stands
         * that this app must not write over.
         */
        @Volatile var writable = false

        /** The boundaries the background roll and the re-plan signal were last made for ([rollSoon]). */
        val boundaries = BoundaryMemo()

        /** The kept starters' reasons of the last plan, so an unchanged plan skips their homing. Used under [mutex]. */
        val keptReasons = KeptReasonsMemo()

        /** Whether a stored repair record that did not read was dealt with (reported, dropped). Used under [mutex]. */
        var unreadableRecordSeen = false

        /** Whether the rules formats of the stored lineup were told ([noteFormats]). Used under [mutex]. */
        var formatsNoted = false

        /**
         * What this entry knows of the version of the account's file on disk, for the one upgrade copy a save makes
         * ([saveVerified]). Set where the bytes are decoded, and to the version saved once a save returns true. Used
         * under [mutex].
         */
        var disk: Disk = Disk.Unknown
    }

    /** What an [Entry] knows of the file on disk. */
    private sealed interface Disk {
        /** Not read yet, or a save failed or was cut off, so the file may be either: read again before a save. */
        data object Unknown : Disk

        /** There was no file (a new account), or it was set aside or forgotten: nothing to copy. */
        data object NoFile : Disk

        data class Version(
            val value: Int,
        ) : Disk
    }

    /** Where a refresh starts from, and whether its result may be saved. */
    private class Start(
        val current: Lineup?,
        val meta: RefreshMeta,
        val save: Boolean,
        // True only when the store held no file and the session held no lineup: a new account, not a lost one.
        val isNew: Boolean = false,
        val repairs: TodayRepairs = TodayRepairs.NONE,
        // Why [save] is false: a transient read failure (a retry can fix it) differs from a file that stays unwritable.
        val readOnly: ReadOnly? = null,
    )

    /** Why a lineup may not be written. */
    private enum class ReadOnly { NewerVersion, ReadFailed, LeftAlone }

    /** What a locked entry holds once read: the start, the held (reconciled) lineup, and the lineup to work from. */
    private data class Loaded(
        val start: Start,
        val held: Revisioned?,
        val lineup: Lineup?,
    )

    private val lock = Any()
    private val entries = HashMap<LibraryAccount, Entry>()

    // Process-global, across accounts, so two accounts never share a revision number. BuiltInScheduleSource keys its
    // days by what they depend on, not by this; it uses the revision to tell whether a lineup is still current.
    private var revisions = 0L
    private val noLineup: StateFlow<Revisioned?> = MutableStateFlow<Revisioned?>(null).asStateFlow()

    /**
     * Brings [account]'s lineup up to date with the library's [libraries]: after a sync, or at start when it is
     * absent, unfinalised or behind the catalog. Never throws for stored data.
     *
     * Before the planning read it takes what the lineup will be recorded as planned from: the library's content
     * generation and the latest completed collection pass. They are stored with the lineup ([RefreshMeta]) only when
     * the plan is saved or published, so a refresh that throws records nothing and is tried again while the
     * generation still differs. The pass counts as taken in only when the planning read holds the collections
     * (`RuleSnapshot.collectionsSynced`).
     *
     * The first choice, and a newer catalog, wait for the collection pass. If at least [GIVE_UP_AFTER] collection
     * passes have ended without completing since the last that did, the lineup is planned without them, once per
     * refresh that finds the same: a pass that never completes must not keep an unfinalised lineup empty for good, nor
     * hold a finalised one at an older catalog (so the grown-up genre exclusions reach every household). The count is
     * read here, so which trigger asked, and in what order, does not matter. The collections reach the lineup later
     * through the hint pass of the next refresh that holds them.
     */
    internal suspend fun refresh(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) {
        refreshing(account, libraries, rebuildDays = false)
    }

    /**
     * A refresh that plans as the boundary that began today would have, and drops every day built from the lineup
     * ([forgetDerived]: the kept and remembered days in memory, and the days on disk), so the new plan and today's
     * schedule apply from the start of today, now, for every channel. For the debug and benchmark command that does it
     * (#812). A refresh starts the changes it makes at the next boundary; this starts them at today's (the planner is
     * given the moment before today's day start as its now, and that day start as its boundary). What an earlier plan
     * left waiting for the next boundary is brought to today's start first ([withPendingBroughtForward]), and what the
     * plan makes is rolled as of that start, so nothing is left pending for tomorrow. The lineup is otherwise the one
     * [refresh] plans, and the viewer's edits and channels are kept (it is not a reset). The repair record is kept too
     * ([Start.repairs], as in a refresh): a reset clears it because it replaces the lineup, so the repairs of the
     * blocks it had would name channels and days that are gone, but a rebuild keeps the same channels, and a repair is
     * the viewer's own fix of a block that left the library, which is as true after the rebuild as before it. The days
     * go before the new revision is published, so whatever reads the lineup next builds them from the planned lineup.
     * Under the account's lock, like a refresh.
     */
    internal suspend fun rebuildToday(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Boolean = refreshing(account, libraries, rebuildDays = true)

    private suspend fun refreshing(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        rebuildDays: Boolean,
    ): Boolean {
        val owner =
            ownerOf(account) ?: run {
                report(LineupEvent.NonCanonicalAccount)
                return false
            }
        val entry = entryOf(owner)
        val began = TimeSource.Monotonic.markNow()
        val savedSeq = savedSeqNow()
        var published = true
        entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            val (planned, meta) = planned(owner, account, libraries, start, sinceDayStart = rebuildDays)
            val saving = recordSaved(owner, entry, planned, meta, start.repairs, savedSeq)
            // A refresh whose save failed does not publish what it planned (Phil's decision on #648): the lineup
            // still on disk stays the one in use, so the screen matches what is saved, and the next refresh plans
            // again (the planned meta was never published, so it still looks out of date). With no lineup to keep
            // (the first one) there is nothing to fall back to, so it is published as it was planned.
            if (saving.failed && start.current != null) {
                published = false
                return@withLock
            }
            val repairs = saving.repairs
            if (rebuildDays) withContext(NonCancellable) { forgetDerived(owner.key, signOut = false) }
            publish(entry, planned, meta, repairs)
            // Again after the publish: a build that began between the first drop and the publish read the old lineup
            // and must keep nothing of it either (the epoch it holds is the one before this).
            if (rebuildDays) withContext(NonCancellable) { forgetDerived(owner.key, signOut = false) }
            // Only now: a refresh that fails before publishing (the planner throws) leaves the stored lineup to load().
            entry.loaded = true
        }
        // Only a refresh that ran to its publish is timed: load to publish, including any wait on the account's lock.
        if (published) seams.timed(began.elapsedNow().inWholeMilliseconds)
        return published
    }

    /**
     * The lineup planned from [start]'s lineup (null: from nothing) against the library's [libraries] now, and the meta
     * to record with it: its pass is kept unless the planning read took in a newer collection pass, and its extras
     * boundary is kept once it has one (see [plannedFrom]). Reads as
     * documented on [refresh]. Call under the account's lock.
     */
    private suspend fun planned(
        owner: Owner,
        account: LibraryAccount,
        libraries: Set<ItemId>,
        start: Start,
        sinceDayStart: Boolean = false,
    ): Pair<Lineup, RefreshMeta> =
        readForPlan(account, libraries).let { read ->
            plannedFrom(
                owner,
                read,
                libraries,
                start.current,
                start.meta,
                newAccount = start.isNew,
                sinceDayStart = sinceDayStart,
            )
        }

    /** What the library says at the moment of a plan. */
    private class PlanRead(
        val generation: Long,
        val pass: Instant?,
        val now: Instant,
        val snapshot: RuleSnapshot,
        val names: CollectionNames,
        val giveUp: Boolean,
    )

    private suspend fun readForPlan(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): PlanRead {
        // Before the read, so a write that lands during it leaves the generation behind and is seen next time.
        val generation = library.contentGeneration(account)
        val pass = library.collectionPassCompleted(account).first()
        val now = clock.now()
        // The planner always ranks without the extras (spec 23): a clip or an opening is no hours of a channel.
        val snapshot = ExtrasFilter.filter(library.ruleSnapshot(account, libraries, now)).snapshot
        seams.unknownSeries(episodesWithUnknownSeries(snapshot))
        val names = library.collectionNames(account)
        // Read now, whatever trigger asked: the lineup stops waiting for collections that are not coming.
        val giveUp = library.failedCollectionPasses(account) >= GIVE_UP_AFTER
        return PlanRead(generation, pass, now, snapshot, names, giveUp)
    }

    @Suppress("LongParameterList") // What one plan reads and carries, one for one.
    private fun plannedFrom(
        owner: Owner,
        read: PlanRead,
        libraries: Set<ItemId>,
        current: Lineup?,
        before: RefreshMeta,
        kept: List<LineupChannel> = emptyList(),
        offsetFrom: Lineup? = current,
        newAccount: Boolean = false,
        sinceDayStart: Boolean = false,
        household: Boolean? = null,
    ): Pair<Lineup, RefreshMeta> {
        // A lineup not yet finalised is chosen afresh, so the viewer's channels in it are kept like a reset's.
        val own =
            current
                ?.takeIf { !it.finalised }
                ?.channels
                .orEmpty()
                .filter { it.isTheViewers() }
        val nowMs = read.now.toEpochMilliseconds()
        // What is past at the read is taken off first (`rolled`); the changes this plan makes start at the next day
        // boundary, worked out here with the lineup's own offset and passed in (the engine knows no scheduler).
        // [sinceDayStart] (the debug rebuild, #812) plans as the boundary that began today would have: it works as of
        // the moment before today's day start and starts its changes there, so they apply now, not tomorrow. Every
        // other plan works as of now and starts its changes at the next boundary.
        val rolledNow = current?.rolled(nowMs)
        val boundaryMs = nextBoundary(offsetFrom ?: rolledNow, nowMs)
        val dayStartMs = dayStart(offsetFrom ?: rolledNow, nowMs)
        val planNowMs = if (sinceDayStart) dayStartMs - 1 else nowMs
        val planBoundaryMs = if (sinceDayStart) dayStartMs else boundaryMs
        // What the plan before left waiting for the next boundary (a refresh at start records it) is brought to today's
        // start too, or the engine would keep it pending as it does for every plan.
        val rolled =
            if (sinceDayStart) {
                current?.withPendingBroughtForward(boundaryMs, dayStartMs)?.rolled(planNowMs)
            } else {
                rolledNow
            }
        // #306: a finalised lineup is never shelved or reshaped from a snapshot with no items. That is a library not
        // synced (yet), not an empty one: keep the lineup as rolled and the meta passed in, so the generation is not
        // recorded and the next trigger plans again. A selection that really became empty is the selection-change
        // repair's to handle, not a plan's. Every plan of a current lineup passes here (refresh and edit).
        if (rolled != null && rolled.finalised && read.snapshot.items.isEmpty()) return rolled to before
        val plan =
            LineupPlanner.plan(
                rolled,
                owner.server,
                owner.user,
                standardOffsetMinutes(),
                read.snapshot,
                read.names,
                planNowMs,
                planBoundaryMs,
                catalog,
                withoutCollections = read.giveUp,
                kept = (kept + own).distinctBy { it.id },
                // A setting survives a reset: one planned from nothing takes the replaced lineup's flag (the planner
                // keeps a current lineup's own).
                useMyCollections = household ?: offsetFrom?.useMyCollections ?: true,
                snapshotGeneration = read.generation,
                keptReasons = entryOf(owner).keptReasons,
            )
        val planned = if (sinceDayStart) plan.rolled(dayStartMs) else plan
        val taken = read.pass?.takeIf { read.snapshot.collectionsSynced }?.toEpochMilliseconds()
        val meta =
            RefreshMeta(
                plannedGeneration = read.generation,
                collectionPassActedOn = taken ?: before.collectionPassActedOn,
                plannedLibraries = libraries.map { it.value }.sorted(),
                extrasFromMs = extrasBoundary(before.extrasFromMs, offsetFrom, newAccount, nowMs),
                replanFromMs = planned.replanFromMs(),
                plannedWithoutCollections = before.plannedWithoutCollections || read.giveUp,
            )
        return planned to meta.copy(plannedWithoutCollections = meta.givenUpMatters(planned))
    }

    internal fun nowMs(): Long = clock.now().toEpochMilliseconds()

    private val replans =
        MutableSharedFlow<LibraryAccount>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * An account whose pending household change has reached its boundary, said by the background roll (which only
     * signals: it never plans under its try-lock). The refresher listens and refreshes.
     */
    internal val replanRequests: Flow<LibraryAccount> get() = replans

    /**
     * When the extras filter starts. A [stored] boundary stands unless it is more than a day after [nowMs] (a clock
     * that was wrong when it was set): that is as good as none. With none, a lineup that is already there (or was, and
     * could not be read) keeps today as it is and starts at the next broadcast-day start; only an account that never
     * had a lineup ([newAccount]) filters from the start of time.
     */
    private fun extrasBoundary(
        stored: Long?,
        basis: Lineup?,
        newAccount: Boolean,
        nowMs: Long,
    ): Long =
        when {
            stored != null && stored <= nowMs + MS_PER_DAY -> stored
            newAccount -> Long.MIN_VALUE
            else -> nextBoundary(basis, nowMs)
        }

    /**
     * The next broadcast-day start after [nowMs]: [LineupDefaults.DAY_START] at the offset of [lineup], else the device
     * zone's standard one, by the same epoch arithmetic the days use. A day that has begun is not the next one.
     */
    internal fun nextBoundary(
        lineup: Lineup?,
        nowMs: Long,
    ): Long = epochBoundary(lineup, nowMs, 1L)

    /** The start of the broadcast day [nowMs] is in (the boundary that began today), by [nextBoundary]'s arithmetic. */
    internal fun dayStart(
        lineup: Lineup?,
        nowMs: Long,
    ): Long = epochBoundary(lineup, nowMs, 0L)

    private fun epochBoundary(
        lineup: Lineup?,
        nowMs: Long,
        epochsAhead: Long,
    ): Long {
        val offset = lineup?.utcOffsetMinutes ?: standardOffsetMinutes()
        val server = lineup?.serverId ?: NO_SERVER
        val recipe = catalog.leftovers.firstOrNull() ?: LeftoversRecipe(STAND_IN_KEY, STAND_IN_KEY, "", 0, null)
        val settings = RecipeResolver.leftoversSettings(recipe, server, offset)
        return Epochs.epochStart(settings, Epochs.epochNumber(settings, nowMs) + epochsAhead)
    }

    /**
     * Records [pass] as taken in by [account]'s lineup without planning again: the refresher found nothing in the
     * library to plan from since the lineup last took in a pass, so the lineup already reflects this one too. Without
     * this a pass that wrote nothing would look new after every restart. Only a lineup that already took in a pass is
     * touched. It changes the [RefreshMeta] and nothing else, so the revision does not move and the lineup saved is
     * the one this session holds, never an older one read back from the file. A session lineup that was not published
     * writable (planned while the file could not be read) is not that one: the file, now readable, is.
     * Never throws for stored data; saves under
     * the same rules as [refresh].
     */
    internal suspend fun notePass(
        account: LibraryAccount,
        pass: Instant,
    ) {
        val owner = ownerOf(account) ?: return
        val entry = entryOf(owner)
        val savedSeq = savedSeqNow()
        entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            val held = reconciled(entry, start)
            val lineup = held?.lineup ?: start.current
            val meta = held?.meta ?: start.meta
            val ms = pass.toEpochMilliseconds()
            val acted = meta.collectionPassActedOn
            if (lineup != null && acted != null && acted != ms) {
                val updated = meta.copy(collectionPassActedOn = ms)
                val repairs = savedWithRecord(owner, entry, lineup, updated, held?.repairs ?: start.repairs, savedSeq)
                if (held == null) {
                    publish(entry, lineup, updated, repairs)
                } else {
                    entry.set(held.copy(meta = updated, repairs = repairs, writable = entry.writable))
                }
                entry.loaded = true
            } else {
                noteWritable(entry)
            }
        }
    }

    /**
     * Applies [edit] to [account]'s lineup (spec 21) under the account's lock, so it never lands between a refresh's
     * read and its save: it waits for the refresh and edits what the refresh published. It starts from the lineup
     * this session published writable, else from the stored one ([startOf]): a lineup planned while the file could
     * not be read is never saved over the real one. Like [refresh] it may set an unreadable or foreign file aside
     * first. An applied edit is saved and then published with a new revision; one that changes nothing, and a
     * refusal, publish no edit. [Saved.Failed]: the save failed or was rejected. [Saved.Invalid]: the edit would break
     * the lineup's rules. Null: there is no lineup, or the file may not be written (a newer app's, say). In none of
     * these, nor in a refusal, is the edit made, though a reconcile with the stored lineup ([reconciled]) may already
     * have been published, so that memory matches disk.
     */
    internal suspend fun edit(
        account: LibraryAccount,
        edit: LineupEdit,
        libraries: Set<ItemId>? = null,
    ): Saved<EditOutcome>? {
        val owner = ownerOf(account) ?: return null
        val entry = entryOf(owner)
        val savedSeq = savedSeqNow()
        return entry.mutex.withLock {
            editLocked(owner, entry, account, edit, libraries, savedSeq, loadedOf(owner, entry))
        }
    }

    // Reads [entry] under its lock: the start, the reconciled held lineup, and the lineup to work from.
    private suspend fun loadedOf(
        owner: Owner,
        entry: Entry,
    ): Loaded {
        val start = startOf(owner, entry)
        entry.writable = start.save
        val held = reconciled(entry, start)
        return Loaded(start, held, held?.lineup ?: start.current)
    }

    // [edit], under the account's lock (held by the caller), from what [loadedOf] read.
    @Suppress("LongParameterList") // What one edit reads and carries.
    private suspend fun editLocked(
        owner: Owner,
        entry: Entry,
        account: LibraryAccount,
        edit: LineupEdit,
        libraries: Set<ItemId>?,
        savedSeq: SavedSeqs?,
        loaded: Loaded,
    ): Saved<EditOutcome>? {
        val (start, held, lineup) = loaded
        if (!entry.writable || lineup == null) {
            noteWritable(entry)
            return null
        }
        val outcome = applied(lineup, edit)
        return when {
            outcome == null -> {
                noteWritable(entry)
                Saved.Invalid
            }

            // A refusal, or an edit that changes nothing: no save and no new revision (no schedule rebuild).
            outcome !is EditOutcome.Applied || outcome.lineup == lineup -> {
                noteWritable(entry)
                Saved.Ok(outcome)
            }

            else -> {
                val before = held?.meta ?: start.meta
                // "Use my collections" changes which starters fill, so the lineup is planned again from the edited
                // one, in the same lock and with one save. A library that cannot be read saves the edit alone.
                val replan = replanned(owner, account, edit, outcome, libraries, before)
                val edited = replan.outcome
                // Only a plan clears a pending re-plan: an edit that did not plan keeps the one that was due.
                val kept = edited.lineup.replanFromMs() ?: before.replanFromMs
                val meta = if (replan.planned) replan.meta else before.copy(replanFromMs = kept)
                // Published only once saved: a lineup that is not on disk would be undone by the next refresh.
                val trimmed = trimmed(edited.lineup, meta, held?.repairs ?: start.repairs, nowMs(), savedSeq)
                val repairs = trimmed.record
                if (saveVerified(owner, edited.lineup, meta, repairs)) {
                    told(trimmed)
                    publish(entry, edited.lineup, meta, repairs)
                    entry.loaded = true
                    Saved.Ok(edited)
                } else {
                    Saved.Failed
                }
            }
        }
    }

    // The applied [outcome] planned again against the library when [edit] is the household switch and [libraries] is
    // known; else (or when the library or the plan fails, which is reported) the outcome and [before] as they are.
    @Suppress("TooGenericExceptionCaught", "LongParameterList") // Any failure of the read or plan: report and keep.
    private suspend fun replanned(
        owner: Owner,
        account: LibraryAccount,
        edit: LineupEdit,
        outcome: EditOutcome.Applied,
        libraries: Set<ItemId>?,
        before: RefreshMeta,
    ): Replan {
        if (edit !is LineupEdit.UseMyCollections || libraries.isNullOrEmpty()) return Replan(outcome, before, false)
        return try {
            val read = readForPlan(account, libraries)
            val (planned, meta) = plannedFrom(owner, read, libraries, outcome.lineup, before)
            Replan(EditOutcome.Applied(planned), meta, planned = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.RefreshFailed)
            Replan(outcome, before, planned = false)
        }
    }

    // Null: the edit could not build a lineup that keeps the invariants (the edits are meant to make that impossible).
    @Suppress("SwallowedException")
    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): EditOutcome? =
        try {
            // On the lineup as it is now (`rolled`): a change of the curated on-set starts at the next day boundary.
            val nowMs = clock.now().toEpochMilliseconds()
            val rolled = lineup.rolled(nowMs)
            LineupEdits.apply(rolled, edit, nowMs, nextBoundary(rolled, nowMs), catalog)
        } catch (_: IllegalArgumentException) {
            report(LineupEvent.EditInvalid)
            null
        }

    /**
     * Replaces [account]'s lineup with a fresh first choice from the [libraries], under the account's lock, keeping the
     * viewer's own channels (spec 22, rulings 18, 23, 24): those of the lineup `edit` would read ([reconciled]).
     * That is the session's own lineup when it holds a writable one (its channels survive a stored file that has
     * become unreadable and is set aside), else the stored one; none when nothing is held and the file was set
     * aside. They are given to the planner, which plans the starters around them. Like [refresh] it may set an
     * unreadable or foreign file aside first. The old lineup stays until the new one is
     * planned and saved; the save, the forgetting of what was built from the old one and the publishing then finish
     * even if the caller is cancelled, so the lineup is never left forgotten or half replaced. [Saved.Failed]: the
     * save failed or was rejected, and nothing changed. [Saved.Unavailable]: the library could not be read, and nothing
     * changed. Null: the
     * file may not be written, and nothing changes. Never throws for stored data; other failures (the planner's, say)
     * propagate, and `ChannelManager` turns them into `ResetResult.Failed`.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure to read the library: nothing is written and it is reported.
    internal suspend fun reset(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Saved<Lineup>? {
        val owner = ownerOf(account) ?: return null
        val entry = entryOf(owner)
        return entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            if (!entry.writable) {
                noteWritable(entry)
                return@withLock null
            }
            val read =
                try {
                    readForPlan(account, libraries)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    report(LineupEvent.ResetLibrariesUnavailable)
                    return@withLock Saved.Unavailable
                }
            // What `edit` reads, so a lineup planned while the file could not be read is never what is kept.
            val previous = reconciled(entry, start)
            val replaced = previous?.lineup ?: start.current
            val kept = replaced?.channels.orEmpty().filter { it.isTheViewers() }
            // A reset replaces a lineup that exists: the filter keeps the boundary that lineup had, or starts at the
            // next day start. With no lineup at all it is a new account's, planned from the start.
            val before = RefreshMeta(extrasFromMs = (previous?.meta ?: start.meta).extrasFromMs)
            val never = start.isNew && replaced == null
            val (fresh, meta) = plannedFrom(owner, read, libraries, null, before, kept, replaced, never)
            withContext(NonCancellable) {
                // A reset forgets the days built from the old lineup, and with them what was repaired of them.
                if (saveVerified(owner, fresh, meta, TodayRepairs.NONE)) {
                    forgetDerived(owner.key, signOut = false)
                    publish(entry, fresh, meta, TodayRepairs.NONE)
                    entry.loaded = true
                    Saved.Ok(fresh)
                } else {
                    // The old lineup and its days stay: a lineup that is not on disk would be undone by a refresh.
                    Saved.Failed
                }
            }
        }
    }

    // [Revisioned.writable] means "this held lineup is the one to save from". A held lineup that was not published
    // writable (planned while the file could not be read) is replaced, once the file reads again, by the stored one,
    // under a new revision, so memory matches disk before anything is saved. Null: nothing held to save from.
    private fun reconciled(
        entry: Entry,
        start: Start,
    ): Revisioned? {
        val held = entry.state.value
        return when {
            held?.writable == true && entry.writable -> {
                held
            }

            held != null && start.save && start.current != null -> {
                publish(entry, start.current, start.meta, start.repairs)
                entry.state.value
            }

            else -> {
                noteWritable(entry)
                null
            }
        }
    }

    // The flag may only be lowered here, never raised: a lineup becomes writable by being published from disk or after
    // a successful save.
    private fun noteWritable(entry: Entry) {
        val held = entry.state.value
        if (held != null && held.writable && !entry.writable) entry.set(held.copy(writable = false))
    }

    /** Reports [event] as the service's own diagnostics do. */
    internal fun reportEvent(event: LineupEvent) = report(event)

    /**
     * How many items of the [libraries] could air in a switched-on channel's day at [at] and yet air nowhere: in no
     * channel's day, and not held back on purpose (an opt-in recipe's or a hidden channel's). A check that the catalog
     * does its job: zero while the leftovers channels are on, apart from what a grown-up pool drops (see
     * [turnedAwayByBothHoldsCount]). Zero while there is no lineup. Reads the library as the days are built (one
     * `ruleSnapshot` per distinct cut-off).
     */
    internal suspend fun airingNowhereCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Int {
        val held = loaded(account) ?: return 0
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds(), held.meta.extrasFromMs)
        val read = snapshotsOf(library, account, libraries, scheduled.map { it.cut }.distinct().sorted())
        return days.airingNowhere(held.lineup, scheduled, read.first, read.second)
    }

    /**
     * How many of the items [airingNowhereCount] leaves out are ones a grown-up pool drops
     * (`GrownUpHold.dropsFromGrownUp`: made for kids and takeable by a kid channel) and air nowhere. Under
     * `grown-up-hold-4` (ADR-0020, option C) this is normally zero; it remains as a guard, counted apart.
     */
    internal suspend fun turnedAwayByBothHoldsCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Int {
        val held = loaded(account) ?: return 0
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds(), held.meta.extrasFromMs)
        val read = snapshotsOf(library, account, libraries, scheduled.map { it.cut }.distinct().sorted())
        return days.turnedAwayByBothHolds(held.lineup, scheduled, read.first, read.second)
    }

    /**
     * Forgets [account]'s lineup, for an explicit user action only: the in-memory lineup goes first, then the stored
     * file, so a refresh afterwards plans from scratch and never saves the old lineup back. This is the only way to
     * delete a lineup; nothing else may call `LineupStore.forget`. What was built from it ([LineupSeams.derived]: the
     * days on disk) is forgotten with it. True when no stored lineup remains.
     */
    internal suspend fun forget(account: LibraryAccount): Boolean {
        val owner = ownerOf(account) ?: return false
        val entry = entryOf(owner)
        return entry.mutex.withLock {
            entry.set(null)
            entry.loaded = false
            entry.writable = false
            store.forget(owner.key).also {
                entry.disk = if (it) Disk.NoFile else Disk.Unknown
                forgetDerived(owner.key)
            }
        }
    }

    // The days built from the lineup go after it: the days' own store and what was registered with [alsoForget],
    // never an AccountCache that could reach the library, and a fault there (none throws, but) cannot undo the
    // lineup's delete or reset. Cancellation goes through. [signOut] is true only when the lineup itself is deleted
    // ([forget]): the store's `forget` then also refuses the account's later writes. A rebuild or reset deletes the
    // day files one by one instead, so the days built next are still saved (#815).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun forgetDerived(
        account: LibraryAccount,
        signOut: Boolean = true,
    ) {
        try {
            forgetters.forEach { it(account) }
            val days = seams.derived
            if (signOut) {
                days?.forget(account)
            } else {
                days?.names(account)?.forEach { days.delete(account, it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort: the days are a cache. A lineup planned again builds them again, under a key of its own.
        }
    }

    private val forgetters = CopyOnWriteArrayList<(LibraryAccount) -> Unit>()

    /**
     * [forget] is told the (canonical) account whenever its lineup is forgotten or reset, beside
     * [LineupSeams.derived]: what a schedule source holds in memory of the days built from it. It must not throw.
     */
    internal fun alsoForget(forget: (LibraryAccount) -> Unit) {
        forgetters += forget
    }

    // The seqs saved with the TV's selection, for a roll to prune the repairs they supersede; null while unknown.
    @Volatile private var savedSeq: suspend () -> SavedSeqs? = { null }

    /**
     * [seqs] gives the seq and the reset seq saved with the TV's selection (the schedule source's): a roll of the
     * record then prunes the selection repairs a saved later seq has superseded, and those below the reset seq
     * (`TodayRepairs.pruned`), and a trim drops the repairs not shown first. Without it nothing is pruned.
     */
    internal fun savedSeqFrom(seqs: suspend () -> SavedSeqs?) {
        savedSeq = seqs
    }

    // The seq of the selection change whose save is under way (the repairer's turn), or null.
    @Volatile private var savingSeq: Long? = null

    /**
     * Told by the repairer the seq whose save is under way ([seq]), and null once the save is over: until then a trim
     * counts that seq's repairs as shown, so a save of another kind near the cap does not drop the change being saved.
     * Pruning never uses it (the repair shown now must stay until the save is over).
     */
    internal fun saving(seq: Long?) {
        savingSeq = seq
    }

    /** For tests: the seq whose save is under way ([saving]), or null. */
    internal val savingNow: Long? get() = savingSeq

    // The saved seq now, read before the entry's lock is taken (the provider is the schedule source's, which must not
    // wait on it): null when unknown or when the read fails, and then a trim counts every selection repair as shown.
    @Suppress("TooGenericExceptionCaught") // Any failure of the provider: the save goes on without the seq.
    private suspend fun savedSeqNow(): SavedSeqs? =
        try {
            savedSeq()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** [account]'s lineup as this service holds it now; null while it has none. */
    internal fun lineup(account: LibraryAccount): StateFlow<Revisioned?> =
        ownerOf(account)?.let { entryOf(it).state.asStateFlow() } ?: noLineup

    /** What [recordRepairsOutcome] did. */
    internal enum class Recorded {
        /** The record is the one [recordRepairsOutcome]'s change made, saved with the lineup (or already so). */
        Saved,

        /** The record could not be made to fit the file, or the save failed or was rejected: nothing changed. */
        Refused,

        /** There is no lineup, or this session may not write the account's file: nothing changed. */
        NoLineup,
    }

    /** [recordRepairsOutcome], true only when [Recorded.Saved]. */
    internal suspend fun recordRepairs(
        account: LibraryAccount,
        change: (Revisioned) -> TodayRepairs,
    ): Boolean = recordRepairsOutcome(account, change) == Recorded.Saved

    /**
     * Replaces [account]'s repair record (spec 26) with what [change] makes of the lineup as held now, under the
     * account's lock, saved with the lineup in one write and then published. The record is first rolled (what has
     * ended goes) and made to fit the file: [RepairConfig.fileMargin] stays free under the cap, the oldest fixes go
     * first when it does not fit, never one whose block is on air now, and at most [RepairConfig.maxFixes] stay.
     * [Recorded.NoLineup] when there is no lineup or the file may not be written; [Recorded.Refused] when the record
     * cannot be made to fit, or the save failed or was rejected: then nothing changed (not the file, not the published
     * lineup or record). The first version-4 save keeps a copy of a version-3 file. A change of the in-memory
     * `TodayRepairs.lostCheck` alone is published with no write.
     *
     * A record the format cannot hold is refused at save time (nothing changed): a block that starts before the one
     * before it ends (a negative delta), a block of no length, a negative media start, an index outside an id table,
     * or two repairs of one channel day. Callers that build blocks must keep them in order and non-empty.
     */
    internal suspend fun recordRepairsOutcome(
        account: LibraryAccount,
        change: (Revisioned) -> TodayRepairs,
    ): Recorded {
        val owner = ownerOf(account) ?: return Recorded.NoLineup
        val entry = entryOf(owner)
        return entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            reconciled(entry, start)
            // A stored lineup this session has not published yet is published first, as `loaded` does.
            if (entry.writable && entry.state.value == null && start.current != null) {
                publish(entry, start.current, start.meta, start.repairs)
            }
            val held = entry.state.value?.takeIf { it.writable && entry.writable }
            if (held == null) {
                noteWritable(entry)
                Recorded.NoLineup
            } else {
                record(owner, entry, held, change(held))
            }
        }
    }

    /** The repair engine's tunables (spec 26): the one instance the service and the repairer share. */
    internal val repairConfig: RepairConfig get() = seams.repairConfig

    // [repairs] made to fit beside [lineup] for a save that has nothing to do with them: it never fails for their sake.
    // The selection repairs above [savedSeq]'s seq or below its reset seq (not shown) go first; those of a seq being
    // saved count as shown.
    private fun trimmed(
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
        nowMs: Long,
        savedSeq: SavedSeqs?,
    ): Trimmed {
        val limit = LineupLimits.MAX_FILE_BYTES - seams.repairConfig.fileMargin
        return if (repairs.isEmpty) {
            Trimmed(repairs, 0, 0, 0)
        } else {
            val shown = savedSeq?.let { saved -> maxOf(saved.seq, savingSeq ?: saved.seq) }
            val resetSeq = savedSeq?.resetSeq ?: 0L
            repairs.trimmedToFit(nowMs, limit, shown, resetSeq) { seams.encode(lineup, meta, it).size }
        }
    }

    // What was dropped is told to [LineupSeams.trimmed], as three counts and nothing else: only once it is saved.
    private fun told(trimmed: Trimmed) {
        if (trimmed.dropped > 0) seams.trimmed(trimmed.fixes, trimmed.coveringFixes, trimmed.selection)
    }

    // Saves [lineup] with its record where this session may write, and gives the record to publish with it: the one
    // that was saved (trimmed to fit), so memory equals disk. With no write, or a failed save, the record is kept as
    // it was. The selection repairs above [savedSeq] (read before the lock) are the first a trim drops.
    @Suppress("LongParameterList") // The save's own five, and the saved seq read before the lock.
    private suspend fun savedWithRecord(
        owner: Owner,
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
        savedSeq: SavedSeqs?,
    ): TodayRepairs = recordSaved(owner, entry, lineup, meta, repairs, savedSeq).repairs

    // [savedWithRecord]'s record, and whether a write was tried and failed (not when this session may not write).
    private class Saving(
        val repairs: TodayRepairs,
        val failed: Boolean = false,
    )

    @Suppress("LongParameterList") // The save's own five, and the saved seq read before the lock.
    private suspend fun recordSaved(
        owner: Owner,
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
        savedSeq: SavedSeqs?,
    ): Saving {
        if (!entry.writable) return Saving(repairs)
        val trimmed = trimmed(lineup, meta, repairs, nowMs(), savedSeq)
        val saved = saveVerified(owner, lineup, meta, trimmed.record)
        if (saved) told(trimmed)
        return if (saved) Saving(trimmed.record) else Saving(repairs, failed = true)
    }

    private suspend fun record(
        owner: Owner,
        entry: Entry,
        held: Revisioned,
        wanted: TodayRepairs,
    ): Recorded {
        val nowMs = clock.now().toEpochMilliseconds()
        val limit = LineupLimits.MAX_FILE_BYTES - seams.repairConfig.fileMargin
        val fitted =
            wanted.rolled(nowMs).fitted(nowMs, seams.repairConfig, limit) {
                seams.encode(held.lineup, held.meta, it).size
            }
        return when {
            fitted == null -> {
                Recorded.Refused
            }

            fitted == held.repairs -> {
                Recorded.Saved
            }

            fitted.stored() == held.repairs.stored() -> {
                // Only the lost-block check moved, which the file never holds: nothing to write.
                entry.set(held.copy(repairs = fitted))
                Recorded.Saved
            }

            else -> {
                // Finishes even if the caller is cancelled, so the file and the published record never part.
                withContext(NonCancellable) {
                    if (saveVerified(owner, held.lineup, held.meta, fitted)) {
                        entry.set(held.copy(repairs = fitted))
                        Recorded.Saved
                    } else {
                        Recorded.Refused
                    }
                }
            }
        }
    }

    /** [account]'s repair record (spec 26) as this service holds it now; none while there is no lineup. */
    internal fun repairs(account: LibraryAccount): StateFlow<TodayRepairs> =
        ownerOf(account)?.let { entryOf(it).repairs.asStateFlow() } ?: noRepairs

    private val noRepairs: StateFlow<TodayRepairs> = MutableStateFlow(TodayRepairs.NONE).asStateFlow()

    /**
     * [account]'s lineup, reading the stored one first if this service has not yet: features can show the lineup
     * before any refresh. Only a readable lineup of this version is taken; anything else waits for [refresh].
     */
    internal suspend fun loaded(account: LibraryAccount): Revisioned? {
        val owner = ownerOf(account) ?: return null
        val entry = entryOf(owner)
        if (!entry.loaded) {
            entry.mutex.withLock {
                if (!entry.loaded) {
                    val stored = store.load(owner.key)
                    val decoded = (stored as? StoredBytes.Present)?.let { LineupCodec.decode(it.bytes) }
                    if (decoded is LineupCodec.Decoded.Loaded && owner.owns(decoded.lineup)) {
                        entry.disk = Disk.Version(decoded.fileVersion)
                        droppedUnreadableRecord(owner, entry, decoded)
                        entry.writable = true
                        publish(entry, decoded.lineup, decoded.meta, decoded.repairs)
                        noteFormats(entry, decoded)
                        if (decoded.upgradedRules > 0) rewriteSoon(owner, entry)
                    }
                    entry.loaded = stored != StoredBytes.Failed
                }
            }
        }
        return entry.state.value
    }

    /**
     * Empties [account]'s repair record for a setup (spec 26, Part 4 §2), from what the file holds now: it reads the
     * file itself (never the lineup held in memory, which may be one planned while the file could not be read) and
     * acts on it under the account's lock, in the same hold of it.
     *
     * - A readable lineup of this account: saved with an empty record (every selection repair, every fix; the
     *   lost-block check goes from memory too) and published. [RecordReset.Floor] at the old record's latest seq.
     * - No file: nothing is written. `Floor(0)`.
     * - An unreadable file, or another account's: set aside. `Floor(0)`, or [RecordReset.Failed] when it will not
     *   move.
     * - A newer app's file: left untouched (a newer build's channels must survive a downgrade). `Floor(0)`.
     *
     * In those three, a record held in memory (a session's) is emptied and published too, with no write, and the floor
     * is its latest seq. With a readable lineup the floor is the latest of the file's and memory's.
     * - A read that fails, or a save that fails or is rejected: nothing changes. [RecordReset.Failed].
     *
     * An account whose IDs are not IDs has no file: `Floor(0)`.
     */
    internal suspend fun resetRecord(account: LibraryAccount): RecordReset = onDisk(account, empty = true)

    /**
     * The floor a resume raises the selection seq to (spec 26, Part 4 §2): read like [resetRecord], and under the same
     * lock it writes nothing but the set-aside of an unreadable file or another account's. A readable lineup of this
     * account gives `Floor(latestSeq, record = true)`; no file, a set-aside one and a newer app's give
     * `Floor(0, record = false)` (a newer app's with `newer = true`); a read or a set-aside that fails gives
     * [RecordReset.Failed].
     */
    internal suspend fun floorRecord(account: LibraryAccount): RecordReset = onDisk(account, empty = false)

    // [resetRecord] when [empty], else [floorRecord].
    private suspend fun onDisk(
        account: LibraryAccount,
        empty: Boolean,
    ): RecordReset {
        val owner = ownerOf(account) ?: return RecordReset.Floor(0L, record = false, newer = false)
        val entry = entryOf(owner)
        return entry.mutex.withLock {
            when (val stored = store.load(owner.key)) {
                StoredBytes.Absent -> {
                    entry.disk = Disk.NoFile
                    floorWithoutFile(entry, empty, newer = false)
                }

                StoredBytes.Failed -> {
                    report(LineupEvent.ReadFailedLeftAlone)
                    leftAlone(entry)
                    RecordReset.Failed
                }

                is StoredBytes.Present -> {
                    onDisk(owner, entry, LineupCodec.decode(stored.bytes), empty)
                }
            }
        }
    }

    // The branches of a file that is there. Under the entry's lock.
    private suspend fun onDisk(
        owner: Owner,
        entry: Entry,
        decoded: LineupCodec.Decoded,
        empty: Boolean,
    ): RecordReset =
        when {
            decoded is LineupCodec.Decoded.Loaded && owner.owns(decoded.lineup) -> {
                entry.disk = Disk.Version(decoded.fileVersion)
                noteFormats(entry, decoded)
                if (empty) {
                    emptied(owner, entry, decoded)
                } else {
                    RecordReset.Floor(decoded.repairs.latestSeq(), record = true, newer = false)
                }
            }

            decoded is LineupCodec.Decoded.NewerVersion -> {
                report(LineupEvent.NewerVersionLeftAlone)
                entry.disk = Disk.Version(decoded.version)
                leftAlone(entry)
                floorWithoutFile(entry, empty, newer = true)
            }

            // Unreadable, or another account's lineup: out of the way, as a refresh would.
            store.setAside(owner.key) -> {
                report(LineupEvent.SetAsideUnreadable)
                entry.disk = Disk.NoFile
                floorWithoutFile(entry, empty, newer = false)
            }

            else -> {
                report(LineupEvent.UnreadableLeftAlone)
                leftAlone(entry)
                RecordReset.Failed
            }
        }

    // No readable record of this account on disk. For a reset ([empty]), a record held in memory (a session's, planned
    // while the file could not be read, or one whose file went) is emptied and published, with no write, and its
    // latest seq is the floor; otherwise the floor is 0. [newer]: the file is a newer app's. Under the entry's lock.
    private fun floorWithoutFile(
        entry: Entry,
        empty: Boolean,
        newer: Boolean,
    ): RecordReset {
        val held = entry.state.value
        val repairs = held?.repairs ?: TodayRepairs.NONE
        if (!empty || held == null || repairs == TodayRepairs.NONE) return RecordReset.Floor(0L, record = false, newer)
        entry.set(held.copy(repairs = TodayRepairs.NONE))
        return RecordReset.Floor(repairs.latestSeq(), record = false, newer)
    }

    // A file this session must not write over: the held lineup is no longer one to save from.
    private fun leftAlone(entry: Entry) {
        entry.writable = false
        noteWritable(entry)
    }

    // [decoded]'s lineup saved with an empty record and published. Under the entry's lock; the save finishes even if
    // the caller is cancelled, so the file and the published record never part.
    private suspend fun emptied(
        owner: Owner,
        entry: Entry,
        decoded: LineupCodec.Decoded.Loaded,
    ): RecordReset =
        withContext(NonCancellable) {
            // What memory holds counts too: a seq it used must not come round while its repairs could still show.
            val floor =
                maxOf(
                    decoded.repairs.latestSeq(),
                    entry.state.value
                        ?.repairs
                        ?.latestSeq() ?: 0L,
                )
            droppedUnreadableRecord(owner, entry, decoded)
            entry.writable = true
            if (saveVerified(owner, decoded.lineup, decoded.meta, TodayRepairs.NONE)) {
                publish(entry, decoded.lineup, decoded.meta, TodayRepairs.NONE)
                entry.loaded = true
                RecordReset.Floor(floor, record = true, newer = false)
            } else {
                RecordReset.Failed
            }
        }

    /**
     * Called by a reader (the schedule source) with the lineup it holds: when something in it is past ([rolled]), a
     * roll is attempted in the background. It never waits and never blocks the caller: the days are right without it
     * (`rulesFor`, `householdFor` and `leavesAtMs` give the days of the right rules), so this only tidies the stored
     * lineup. One attempt is made per account and boundary, checked before anything is rolled: a session that may not
     * save starts none, and a save that failed is not tried on every read. An attempt that finds the lock busy (a
     * refresh or an edit holds it, and rolls under it anyway) does not count, so a later lookup tries again. The roll
     * works from the held lineup and reads nothing from the store.
     *
     * A household change that has reached its boundary ([RefreshMeta.replanDue]) is said to the refresher
     * ([replanRequests]) while nothing holds the lock, once per boundary, whether or not there is anything to roll:
     * the refresher plans, never this lookup.
     */
    internal fun rollSoon(
        account: LibraryAccount,
        held: Revisioned,
    ) {
        val owner = ownerOf(account) ?: return
        val entry = entryOf(owner)
        val nowMs = clock.now().toEpochMilliseconds()
        val replanDue = held.meta.replanDue(nowMs)
        val canSave = held.writable && entry.writable
        if (replanDue || canSave) {
            val boundary = nextBoundary(held.lineup, nowMs)
            if (replanDue) signalReplan(entry, account, boundary)
            // Cheap checks first, so a lineup that cannot be saved is not copied (`rolled`) on every lookup.
            val untried = canSave && !entry.boundaries.rollTried(boundary)
            // Only an untried boundary pays for the look: once tried, a lookup does no rolling work at all.
            val due =
                untried &&
                    (
                        seams.rolledLineup(held.lineup, nowMs) !== held.lineup ||
                            held.repairs.rolled(nowMs) !== held.repairs
                    )
            // One attempt per boundary: the first lookup to see it starts it.
            if (due && entry.boundaries.firstRoll(boundary)) rolls.launch { rollHeld(owner, entry, nowMs, boundary) }
        }
    }

    // At most once per boundary, and only while nothing holds the lock (a refresh or an edit under way plans anyway,
    // and a later lookup asks again if the change is still due).
    private fun signalReplan(
        entry: Entry,
        account: LibraryAccount,
        boundary: Long,
    ) {
        if (!entry.mutex.isLocked && entry.boundaries.firstSignal(boundary)) replans.tryEmit(account)
    }

    // Under tryLock: the lineup is rolled from what is held now (re-checked here), saved, then published. A session
    // that may not write does nothing; a failed save leaves the held lineup as it was. A busy lock un-records the
    // attempt, so a later lookup tries again. Anything else that throws is reported: the roll scope has no handler.
    @Suppress("TooGenericExceptionCaught") // Any failure of a background roll: report it, the days are right anyway.
    private suspend fun rollHeld(
        owner: Owner,
        entry: Entry,
        nowMs: Long,
        boundary: Long,
    ) {
        // Read before the lock is tried: the provider never runs under the entry's lock.
        val applied = savedSeqNow()
        if (!entry.mutex.tryLock()) {
            entry.boundaries.rollSkipped(boundary)
            return
        }
        try {
            val held = entry.state.value
            val rolled = held?.lineup?.rolled(nowMs)
            val rolledRepairs = held?.repairs?.rolled(nowMs)?.prunedAt(applied)
            // The roll never does the first version-3 write: a lineup decoded from a version 1 or 2 file has none of
            // the fields `rolled` clears (no leavesAtMs, previous rules or basis), so it comes back unchanged and is
            // not saved here. Any save, this one included, keeps the copy of an older file first ([saveVerified]),
            // from the version the entry knows is on disk (read again when a save before it failed). It relies on
            // `entry.writable` as the last `startOf` under the lock set it.
            // The repair record is pruned with it: a day that has ended is no use to anyone.
            val changed = held != null && (rolled !== held.lineup || rolledRepairs !== held.repairs)
            val due = held != null && changed && held.writable && entry.writable
            val trimmed =
                rolledRepairs?.takeIf { due }?.let { trimmed(checkNotNull(rolled), held.meta, it, nowMs, applied) }
            if (trimmed != null && saveVerified(owner, checkNotNull(rolled), held.meta, trimmed.record)) {
                told(trimmed)
                publish(entry, rolled, held.meta, trimmed.record)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.SaveFailed)
        } finally {
            entry.mutex.unlock()
        }
    }

    /**
     * The one-time rewrite (ADR-0026, Versioning): once the format 4 gate has passed, a stored lineup whose rules its
     * read upgraded (rules that say 3 and hold a studio feature now say 4) is saved once, so the file says 4 too and no
     * TV reads those rules under studio fold 1 again. [loaded] asks for it; it runs in the background, never on a
     * reader's path (a tune's lookup reads through [loaded]), and takes the account's lock like any save.
     *
     * Under the lock it reads the file again and saves only when the file still holds rules to upgrade (a refresh or
     * an edit since may have saved them already), and only the lineup this session holds writable, through the normal
     * save ([savedWithRecord]: the same checks every save makes). A later start reads 4 and asks for nothing, so this
     * runs once. A file of an older lineup version keeps its copy first, as any first save of a new version does. A
     * failure is reported and leaves the file as it was: the next save of the lineup writes 4 anyway.
     */
    private fun rewriteSoon(
        owner: Owner,
        entry: Entry,
    ) {
        rewrites.launch { rewriteUpgraded(owner, entry) }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure of a background save: report it, the next save writes 4.
    private suspend fun rewriteUpgraded(
        owner: Owner,
        entry: Entry,
    ) {
        // Read before the lock is taken: the provider never runs under the entry's lock.
        val savedSeq = savedSeqNow()
        try {
            entry.mutex.withLock {
                val stored = store.load(owner.key) as? StoredBytes.Present
                val decoded = stored?.let { LineupCodec.decode(it.bytes) } as? LineupCodec.Decoded.Loaded
                val due = decoded?.takeIf { it.upgradedRules > 0 && owner.owns(it.lineup) }
                val held = entry.state.value?.takeIf { it.writable && entry.writable }
                if (decoded != null && owner.owns(decoded.lineup)) entry.disk = Disk.Version(decoded.fileVersion)
                if (due != null && held != null) {
                    val repairs = savedWithRecord(owner, entry, held.lineup, held.meta, held.repairs, savedSeq)
                    if (repairs !== held.repairs) entry.set(held.copy(repairs = repairs))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.SaveFailed)
        }
    }

    /** For tests: waits for the one-time rewrites started so far. */
    internal suspend fun awaitRewrites() = rewrites.await()

    /** For tests: waits for the background rolls started so far. */
    internal suspend fun awaitRolls() = rolls.await()

    /** For tests: how many background rolls were started. */
    internal val rollAttempts: AtomicInteger get() = rolls.started

    /** For tests: [account]'s lock, to show that a read never waits on it. */
    internal fun lockOf(account: LibraryAccount): Mutex? = ownerOf(account)?.let { entryOf(it).mutex }

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> {
        val owner = ownerOf(account) ?: return flowOf(emptyList())
        return flow {
            loaded(account)
            emitAll(entryOf(owner).state)
        }.map { held ->
            val nowMs = clock.now().toEpochMilliseconds()
            val day = held?.let { nextBoundary(it.lineup, nowMs) } ?: 0L
            val found = held?.let { playKeys.keysOf(it.revision, day) { days.playKeysOf(it.lineup, nowMs) } }
            val keys = found.orEmpty()
            held
                ?.lineup
                ?.channels
                .orEmpty()
                .sortedWith(CHANNEL_ORDER)
                .map { it.summary(catalog, keys.getValue(it.id), nowMs) }
        }.distinctUntilChanged()
    }

    override fun lineupFinalised(account: LibraryAccount): Flow<Boolean> {
        val owner = ownerOf(account) ?: return flowOf(false)
        return flow {
            loaded(account)
            emitAll(entryOf(owner).state)
        }.map { held -> held?.lineup?.finalised ?: false }.distinctUntilChanged()
    }

    /**
     * [CollectionsAtSetup.chooseAtSetup], under the account's lock. No lineup: the first one is planned with the flag
     * (so the new-account extras boundary is as for any first plan) and saved. A lineup not finalised: the flag is set
     * on it. A finalised one: the `UseMyCollections` edit, in the same lock hold, which starts at the next day
     * boundary (ADR-0023 D1). A lineup that may not be written (or an account with none) is [SetupOutcome.Unwritable].
     */
    override suspend fun chooseAtSetup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
    ): SetupOutcome {
        val owner = ownerOf(account)
        val outcome =
            if (owner == null) {
                SetupOutcome.Unwritable
            } else {
                val savedSeq = savedSeqNow()
                entryOf(owner).mutex.withLock { choosingAtSetup(owner, account, libraries, on, savedSeq) }
            }
        if (outcome == SetupOutcome.Unwritable) report(LineupEvent.SetupSwitchUnwritable)
        return outcome
    }

    // Under the account's lock.
    @Suppress("LongParameterList") // What the setup choice reads and carries, one for one.
    private suspend fun choosingAtSetup(
        owner: Owner,
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
        savedSeq: SavedSeqs?,
    ): SetupOutcome {
        val entry = entryOf(owner)
        val loaded = loadedOf(owner, entry)
        val (start, held, lineup) = loaded
        val before = held?.meta ?: start.meta
        val repairs = held?.repairs ?: start.repairs
        return when {
            !entry.writable -> {
                noteWritable(entry)
                // A transient read failure may clear on a retry; a newer app's file or one left alone will not.
                if (start.readOnly == ReadOnly.ReadFailed) SetupOutcome.Failed else SetupOutcome.Unwritable
            }

            lineup?.finalised == true -> {
                val edited = LineupEdit.UseMyCollections(on)
                if (editLocked(owner, entry, account, edited, libraries, savedSeq, loaded) is Saved.Ok) {
                    SetupOutcome.Applied
                } else {
                    SetupOutcome.Failed
                }
            }

            else -> {
                val planned =
                    if (lineup == null) {
                        firstPlanAtSetup(owner, account, libraries, on, before, start.isNew)
                    } else {
                        lineup.copy(useMyCollections = on) to before
                    }
                planned?.let { (next, meta) -> savedAtSetup(owner, entry, next, meta, repairs, savedSeq) }
                    ?: SetupOutcome.Failed
            }
        }
    }

    @Suppress("LongParameterList") // The save's own six.
    private suspend fun savedAtSetup(
        owner: Owner,
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
        savedSeq: SavedSeqs?,
    ): SetupOutcome {
        val trimmed = trimmed(lineup, meta, repairs, nowMs(), savedSeq)
        val saved = saveVerified(owner, lineup, meta, trimmed.record)
        if (saved) {
            told(trimmed)
            publish(entry, lineup, meta, trimmed.record)
            entry.loaded = true
        }
        return if (saved) SetupOutcome.Applied else SetupOutcome.Failed
    }

    // The first lineup of an account, planned with the switch; null (reported) when the library cannot be read.
    @Suppress("TooGenericExceptionCaught", "LongParameterList") // Any failure to read or plan: nothing is written.
    private suspend fun firstPlanAtSetup(
        owner: Owner,
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
        before: RefreshMeta,
        isNew: Boolean,
    ): Pair<Lineup, RefreshMeta>? =
        try {
            val read = readForPlan(account, libraries)
            plannedFrom(owner, read, libraries, null, before, newAccount = isNew, household = on)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.RefreshFailed)
            null
        }

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> {
        val owner = ownerOf(account) ?: return flowOf(true)
        return flow {
            loaded(account)
            emitAll(entryOf(owner).state)
        }.map { held -> held?.lineup?.useMyCollections ?: true }.distinctUntilChanged()
    }

    /**
     * [FirstLineup.firstLineup]: the active account's lineup, settled once [Revisioned.firstChoiceMade]. An account
     * whose IDs are not IDs never has a lineup, so there is nothing to wait for: settled.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun firstLineup(): Flow<FirstLineupState> =
        library
            .activeAccount()
            .flatMapLatest { account ->
                when {
                    account == null -> flowOf(FirstLineupState.Building)
                    ownerOf(account) == null -> flowOf(FirstLineupState.Settled)
                    else -> firstLineupOf(account)
                }
            }.distinctUntilChanged()

    private fun firstLineupOf(account: LibraryAccount): Flow<FirstLineupState> =
        flow {
            loaded(account)
            emitAll(lineup(account))
        }.map { held -> if (held?.firstChoiceMade == true) FirstLineupState.Settled else FirstLineupState.Building }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun activeChannels(): Flow<List<ChannelSummary>> =
        library.activeAccount().flatMapLatest { account -> if (account == null) emptyFlow() else channels(account) }

    private suspend fun startOf(
        owner: Owner,
        entry: Entry,
    ): Start {
        val held = entry.state.value
        val session = held?.lineup
        val sessionMeta = held?.meta ?: RefreshMeta()
        val sessionRepairs = held?.repairs ?: TodayRepairs.NONE
        return when (val stored = store.load(owner.key)) {
            StoredBytes.Absent -> {
                entry.disk = Disk.NoFile
                Start(session, sessionMeta, save = true, isNew = session == null, repairs = sessionRepairs)
            }

            StoredBytes.Failed -> {
                report(LineupEvent.ReadFailedLeftAlone)
                Start(session, sessionMeta, save = false, repairs = sessionRepairs, readOnly = ReadOnly.ReadFailed)
            }

            is StoredBytes.Present -> {
                startOf(owner, entry, LineupCodec.decode(stored.bytes), Held(session, sessionMeta, sessionRepairs))
            }
        }
    }

    private suspend fun startOf(
        owner: Owner,
        entry: Entry,
        decoded: LineupCodec.Decoded,
        session: Held,
    ): Start =
        when (decoded) {
            is LineupCodec.Decoded.Loaded -> {
                val lineup = decoded.lineup
                if (owner.owns(lineup)) {
                    // Before anything saves (the unreadable record's rewrite below does): this is the version on disk.
                    entry.disk = Disk.Version(decoded.fileVersion)
                    noteFormats(entry, decoded)
                    droppedUnreadableRecord(owner, entry, decoded)
                    Start(lineup, decoded.meta, true, repairs = decoded.repairs)
                } else {
                    setAside(owner, entry, session)
                }
            }

            is LineupCodec.Decoded.NewerVersion -> {
                report(LineupEvent.NewerVersionLeftAlone)
                entry.disk = Disk.Version(decoded.version)
                Start(
                    session.lineup,
                    session.meta,
                    save = false,
                    repairs = session.repairs,
                    readOnly = ReadOnly.NewerVersion,
                )
            }

            LineupCodec.Decoded.Unreadable -> {
                setAside(owner, entry, session)
            }
        }

    // The rules formats of the first lineup of this account the entry decodes from the store, on whatever path reads it
    // first ([loaded], a refresh, an edit, a reset, a record), told once (counts only, ADR-0026): the session that
    // rewrites upgraded rules, or saves them first, is the one that tells how many it upgraded. Under the entry's lock.
    private fun noteFormats(
        entry: Entry,
        decoded: LineupCodec.Decoded.Loaded,
    ) {
        if (entry.formatsNoted) return
        entry.formatsNoted = true
        seams.rulesFormats(RulesFormats.of(decoded.lineup, decoded.upgradedRules))
    }

    // A stored record that did not read (spec 26) is reported once per entry, and the file is written once without it,
    // after a copy of its bytes as they stand is kept. The copy is best effort: the first unreadable record is kept
    // (a copy already there is left as it is, so a later incident's bytes are not), and when the copy cannot be
    // written that is reported and this write is skipped, but the entry stays writable, so the next save of the lineup
    // writes the file without the record and those bytes are gone. Called under the entry's lock, for a lineup this
    // account owns.
    private suspend fun droppedUnreadableRecord(
        owner: Owner,
        entry: Entry,
        decoded: LineupCodec.Decoded.Loaded,
    ) {
        if (!decoded.repairsUnreadable || entry.unreadableRecordSeen) return
        entry.unreadableRecordSeen = true
        report(LineupEvent.RepairsUnreadable)
        if (store.keepCopy(owner.key, UNREADABLE_RECORD_COPY)) {
            saveVerified(owner, decoded.lineup, decoded.meta, TodayRepairs.NONE)
        } else {
            report(LineupEvent.BackupFailed)
        }
    }

    /** What a session held when a start read the store: its lineup, meta and repairs. */
    private class Held(
        val lineup: Lineup?,
        val meta: RefreshMeta,
        val repairs: TodayRepairs,
    )

    // An unreadable file is moved out of the way before anything is saved; one that won't move is never written over.
    private suspend fun setAside(
        owner: Owner,
        entry: Entry,
        session: Held,
    ): Start =
        if (store.setAside(owner.key)) {
            report(LineupEvent.SetAsideUnreadable)
            entry.disk = Disk.NoFile
            Start(null, RefreshMeta(), save = true)
        } else {
            report(LineupEvent.UnreadableLeftAlone)
            Start(session.lineup, session.meta, save = false, repairs = session.repairs, readOnly = ReadOnly.LeftAlone)
        }

    // A lineup is saved only when its own bytes decode back to a lineup of this account, with the repair record and the
    // viewer's marks it was given (a mark the file cannot hold, an Added on a channel that is not an unedited starter,
    // is compared as the Touched it is written as); otherwise the stored file is left as it is, and the in-memory
    // lineup is still published.
    //
    // This is the one place that keeps the copy of an older file: before the bytes of a higher version replace it, the
    // file as it stands is copied under the tag of the pair of versions ([upgradeCopyTag]). The version on disk is the
    // entry's ([Entry.disk]); where that is not known it is read again first. A copy that cannot be written is reported
    // and the save goes on: the lineup in memory is what the viewer sees, and withholding every edit for the sake of
    // a copy would be worse.
    private suspend fun saveVerified(
        owner: Owner,
        planned: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
    ): Boolean {
        val bytes = seams.encode(planned, meta, repairs)
        val back = LineupCodec.decode(bytes)
        val stored = back as? LineupCodec.Decoded.Loaded
        val entry = entryOf(owner)
        return when {
            stored == null || !owner.owns(stored.lineup) || stored.repairs != repairs.stored() ||
                marksOf(planned) != marksOf(stored.lineup) -> {
                report(LineupEvent.SaveRejected)
                false
            }

            !diskKnown(owner, entry) -> {
                false
            }

            else -> {
                keepUpgradeCopy(owner, entry, stored.fileVersion)
                written(owner, entry, bytes, stored.fileVersion)
            }
        }
    }

    // What each channel's mark is once the file has taken it: an Added the file cannot hold is a Touched.
    private fun marksOf(lineup: Lineup): List<ViewerChoice> =
        lineup.channels.map {
            if (it.viewerChoice == ViewerChoice.Added && !storedCanHoldAddedMark(it.source)) {
                ViewerChoice.Touched
            } else {
                it.viewerChoice
            }
        }

    // True when [entry] knows what is on disk. Where it does not (a save failed or was cut off, so the file may be the
    // old bytes or the new), the file is read again: absent, nothing to copy; a lineup of this account, its version;
    // anything else is no file to write over, and nothing is saved (a read that failed stays unknown).
    private suspend fun diskKnown(
        owner: Owner,
        entry: Entry,
    ): Boolean {
        if (entry.disk != Disk.Unknown) return true
        return when (val stored = store.load(owner.key)) {
            StoredBytes.Absent -> {
                entry.disk = Disk.NoFile
                true
            }

            StoredBytes.Failed -> {
                report(LineupEvent.ReadFailedLeftAlone)
                leftAlone(entry)
                false
            }

            is StoredBytes.Present -> {
                when (val decoded = LineupCodec.decode(stored.bytes)) {
                    is LineupCodec.Decoded.Loaded -> {
                        val ours = owner.owns(decoded.lineup)
                        if (ours) {
                            entry.disk = Disk.Version(decoded.fileVersion)
                        } else {
                            // Another account's lineup stands there: not written over, and said so, as `startOf` does.
                            report(LineupEvent.UnreadableLeftAlone)
                            leftAlone(entry)
                        }
                        ours
                    }

                    is LineupCodec.Decoded.NewerVersion -> {
                        report(LineupEvent.NewerVersionLeftAlone)
                        entry.disk = Disk.Version(decoded.version)
                        leftAlone(entry)
                        false
                    }

                    LineupCodec.Decoded.Unreadable -> {
                        report(LineupEvent.UnreadableLeftAlone)
                        leftAlone(entry)
                        false
                    }
                }
            }
        }
    }

    // Copied once, and only for a higher version than the file holds: a file of an older version is copied before the
    // first save of a newer one over it, so a downgrade has something to go back to. A save of the same or a lower
    // version (a version-4 file whose unreadable record is dropped, a roll that clears the last mark) needs no copy.
    private suspend fun keepUpgradeCopy(
        owner: Owner,
        entry: Entry,
        version: Int,
    ) {
        val disk = entry.disk
        if (disk is Disk.Version && version > disk.value) {
            if (!store.keepCopy(owner.key, upgradeCopyTag(disk.value, version))) report(LineupEvent.BackupFailed)
        }
    }

    // The write, after which the entry knows the file is [version]: a save that fails, or is cut off after the bytes
    // may have landed, leaves the version unknown.
    private suspend fun written(
        owner: Owner,
        entry: Entry,
        bytes: ByteArray,
        version: Int,
    ): Boolean {
        var saved = false
        try {
            saved = store.save(owner.key, bytes)
        } finally {
            entry.disk = if (saved) Disk.Version(version) else Disk.Unknown
        }
        if (!saved) report(LineupEvent.SaveFailed)
        return saved
    }

    private fun publish(
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
    ) {
        val revision = synchronized(lock) { ++revisions }
        entry.set(Revisioned(revision, lineup, meta, repairs, entry.writable))
    }

    private fun entryOf(owner: Owner): Entry = synchronized(lock) { entries.getOrPut(owner.key) { Entry() } }
}

/**
 * How many collection passes must have ended without completing before a lineup stops waiting for them: the first
 * lineup for its first choice, and a finalised one for a newer catalog (which it would otherwise never receive).
 */
internal const val GIVE_UP_AFTER = 2

/**
 * The tag of the copy kept of a file of version [from] before one of version [to] is saved over it
 * (`LineupStore.keepCopy`): `v1` and `v2` for the upgrade to 3 and `v3` for the one to 4 are the tags files already on
 * devices carry (the first copy written stays), and every other pair is `v{from}-to{to}` (`v3-to5`, `v4-to5`, and the
 * pairs left when a first save failed).
 */
internal fun upgradeCopyTag(
    from: Int,
    to: Int,
): String {
    val toBase = to == LineupCodec.BASE_VERSION && from < to
    val toRepairs = from == LineupCodec.BASE_VERSION && to == LineupCodec.REPAIRS_VERSION
    return if (toBase || toRepairs) "v$from" else "v$from-to$to"
}

/** What an edit or reset did at the service: it [Ok], or its save failed ([Failed]) or the edit was invalid. */
internal sealed interface Saved<out T> {
    class Ok<T>(
        val value: T,
    ) : Saved<T>

    data object Failed : Saved<Nothing>

    data object Invalid : Saved<Nothing>

    /** A reset could not read the library; nothing changed. */
    data object Unavailable : Saved<Nothing>
}

/**
 * What [LineupService.resetRecord] or [LineupService.floorRecord] found on disk (spec 26, Part 4 §2): a [Floor] for the
 * next selection seq, or [Failed] when the file could not be read, set aside or written.
 */
internal sealed interface RecordReset {
    /**
     * [seq] is the highest selection seq the record held (`TodayRepairs.latestSeq`), 0 when there was none to read.
     * [record] is true when the file held a readable lineup of this account (so a record this build reads), false when
     * there was no file, it was another account's or unreadable (and was set aside), or a newer app's (left alone).
     * [newer] is true only for that last one: a resume that raises the seq over it saves the reset seq too (D17).
     */
    data class Floor(
        val seq: Long,
        val record: Boolean,
        val newer: Boolean,
    ) : RecordReset

    /** The file could not be read, could not be set aside, or the emptied record could not be saved. */
    data object Failed : RecordReset
}

// Pruned at [saved] (`TodayRepairs.pruned`); as it is when the saved seqs are unknown.
private fun TodayRepairs.prunedAt(saved: SavedSeqs?): TodayRepairs = saved?.let { pruned(it.seq, it.resetSeq) } ?: this

/**
 * The seqs saved with the TV's selection, as the service reads them before a save: the [seq] whose repairs show and
 * the [resetSeq] of the last setup, below which no repair shows (`TodayRepairs.applied`).
 */
internal data class SavedSeqs(
    val seq: Long,
    val resetSeq: Long,
)

/** An edit's outcome, its meta, and whether it was [planned] again (only a plan sets the meta afresh). */
private class Replan(
    val outcome: EditOutcome.Applied,
    val meta: RefreshMeta,
    val planned: Boolean,
)

/**
 * Which boundary an account's background roll and its re-plan signal were last made for, so each is made at most
 * once per boundary. A roll that found the lock busy is taken back ([rollSkipped]), so a later lookup tries again.
 */
private class BoundaryMemo {
    private var rollTriedFor: Long? = null
    private var replanSignalledFor: Long? = null

    @Synchronized
    fun rollTried(boundary: Long): Boolean = rollTriedFor == boundary

    /** True for the first roll of [boundary], which it records. */
    @Synchronized
    fun firstRoll(boundary: Long): Boolean = (rollTriedFor != boundary).also { if (it) rollTriedFor = boundary }

    @Synchronized
    fun rollSkipped(boundary: Long) {
        if (rollTriedFor == boundary) rollTriedFor = null
    }

    /** True for the first re-plan signal of [boundary], which it records. */
    @Synchronized
    fun firstSignal(boundary: Long): Boolean {
        val first = replanSignalledFor != boundary
        if (first) replanSignalledFor = boundary
        return first
    }
}

/** The background rolls: their own scope, so a roll outlives the read that asked for it, and a count for tests. */
private class BackgroundRolls(
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val jobs = HashSet<Job>()
    val started = AtomicInteger()

    fun launch(roll: suspend () -> Unit) {
        started.incrementAndGet()
        val job = scope.launch { roll() }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    suspend fun await() = synchronized(jobs) { jobs.toList() }.joinAll()
}

/**
 * What a test can swap in the service: its catalog, how it encodes a lineup, its meta and its repairs for the store,
 * where timings go, what is forgotten with a lineup, and the repair engine's tunables.
 */
@Suppress("LongParameterList") // One seam for each thing a test swaps.
internal class LineupSeams(
    val catalog: StarterCatalogView = StarterCatalog,
    val encode: (Lineup, RefreshMeta, TodayRepairs) -> ByteArray = LineupCodec::encode,
    val timed: (Long) -> Unit = {},
    val unknownSeries: (Int) -> Unit = {},
    val derived: DayStore? = null,
    val rollDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val repairConfig: RepairConfig = RepairConfig(),
    /** Told what a lineup save trimmed from the record to fit: fixes, fixes covering now, selection repairs. */
    val trimmed: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** The lineup as rolled at a moment; here so a test can count the lookups that pay for it. */
    val rolledLineup: (Lineup, Long) -> Lineup = { lineup, nowMs -> lineup.rolled(nowMs) },
    /** Told the rules formats of a stored lineup once, when it is first read (counts only, ADR-0026). */
    val rulesFormats: (RulesFormats) -> Unit = {},
)

/** The episodes of [snapshot] whose series it lacks: what `PoolEvaluator` counts as `episodesWithUnknownSeries`. */
internal fun episodesWithUnknownSeries(snapshot: RuleSnapshot): Int =
    snapshot.items.count {
        it.item.kind == ScheduleItemKind.Episode && it.item.seriesId != null && it.item.seriesId !in snapshot.series
    }

// A channel the viewer built: custom (a retired starter too), or one whose rules need a newer app but whose stored
// source says custom. Only the planner's own channels (starters, leftovers) are planned afresh by a reset.
private fun LineupChannel.isTheViewers(): Boolean =
    when (val source = source) {
        is ChannelSource.Custom -> true
        is ChannelSource.Unusable -> kindOf(source.rawJson) == CUSTOM_KIND
        else -> false
    }

/** An account in canonical form, as the store keys it, with its IDs for the planner. */
private class Owner(
    val key: LibraryAccount,
    val server: CanonicalId,
    val user: CanonicalId,
) {
    /** True when [lineup] is this account's. */
    fun owns(lineup: Lineup) = lineup.serverId == server && lineup.userId == user
}

/** [account] with its IDs in canonical form, as the service keys lineups; null when they are not IDs. */
internal fun canonicalAccount(account: LibraryAccount): LibraryAccount? = ownerOf(account)?.key

/** The canonical form of [account], or null when its server or user ID is not an ID at all. */
private fun ownerOf(account: LibraryAccount): Owner? {
    val server = CanonicalId.parseOrNull(account.server.value)
    val user = CanonicalId.parseOrNull(account.user.value)
    return if (server != null && user != null) {
        Owner(LibraryAccount(ServerId(server.value), UserId(user.value)), server, user)
    } else {
        null
    }
}

// The server a boundary is worked out for when there is no lineup: the day start does not depend on it.
private val NO_SERVER: CanonicalId = CanonicalId.parse("0".repeat(32))

private const val MS_PER_DAY = 86_400_000L

/** The tag of the copy kept of a lineup file whose repair record did not read, before it is written without it. */
internal const val UNREADABLE_RECORD_COPY = "repairs-unreadable"

private const val STAND_IN_KEY = "boundary"
