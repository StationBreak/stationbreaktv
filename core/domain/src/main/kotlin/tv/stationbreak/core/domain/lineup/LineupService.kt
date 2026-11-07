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
import tv.stationbreak.core.engine.lineup.rolled
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
 */
internal data class Revisioned(
    val revision: Long,
    val lineup: Lineup,
    val meta: RefreshMeta = RefreshMeta(),
    val repairs: TodayRepairs = TodayRepairs.NONE,
    val writable: Boolean = false,
)

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
    HouseholdSetting {
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
     * too.
     */
    @Suppress("LongParameterList") // The app's one constructor: the store, the reads and four numbers-only hooks.
    constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
        timed: (Long) -> Unit,
        derived: DayStore? = null,
        unknownSeries: (Int) -> Unit = {},
    ) : this(
        store,
        library,
        clock,
        standardOffsetMinutes,
        report,
        LineupSeams(timed = timed, derived = derived, unknownSeries = unknownSeries),
    )

    internal val catalog: StarterCatalogView get() = seams.catalog
    private val days = LineupDays(seams.catalog)

    // Background rolls (see [rollSoon]): their own scope, so a roll outlives the read that asked for it.
    private val rolls = BackgroundRolls(seams.rollDispatcher)

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
    }

    /** Where a refresh starts from, and whether its result may be saved. */
    private class Start(
        val current: Lineup?,
        val meta: RefreshMeta,
        val save: Boolean,
        // True only when the store held no file and the session held no lineup: a new account, not a lost one.
        val isNew: Boolean = false,
        val repairs: TodayRepairs = TodayRepairs.NONE,
        // The version of the stored file this started from; null when there was none (or it was not read).
        val fileVersion: Int? = null,
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
        val owner = ownerOf(account) ?: return report(LineupEvent.NonCanonicalAccount)
        val entry = entryOf(owner)
        val began = TimeSource.Monotonic.markNow()
        entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            val (planned, meta) = planned(owner, account, libraries, start)
            val repairs = savedWithRecord(owner, entry, planned, meta, start.repairs)
            publish(entry, planned, meta, repairs)
            // Only now: a refresh that fails before publishing (the planner throws) leaves the stored lineup to load().
            entry.loaded = true
        }
        // Only a refresh that ran to its publish is timed: load to publish, including any wait on the account's lock.
        seams.timed(began.elapsedNow().inWholeMilliseconds)
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
    ): Pair<Lineup, RefreshMeta> =
        readForPlan(account, libraries).let { read ->
            plannedFrom(owner, read, libraries, start.current, start.meta, newAccount = start.isNew)
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
        val rolled = current?.rolled(nowMs)
        val planned =
            LineupPlanner.plan(
                rolled,
                owner.server,
                owner.user,
                standardOffsetMinutes(),
                read.snapshot,
                read.names,
                nowMs,
                nextBoundary(offsetFrom ?: rolled, nowMs),
                catalog,
                withoutCollections = read.giveUp,
                kept = (kept + own).distinctBy { it.id },
                // A setting survives a reset: one planned from nothing takes the replaced lineup's flag (the planner
                // keeps a current lineup's own).
                useMyCollections = offsetFrom?.useMyCollections ?: true,
                snapshotGeneration = read.generation,
                keptReasons = entryOf(owner).keptReasons,
            )
        val taken = read.pass?.takeIf { read.snapshot.collectionsSynced }?.toEpochMilliseconds()
        val meta =
            RefreshMeta(
                plannedGeneration = read.generation,
                collectionPassActedOn = taken ?: before.collectionPassActedOn,
                plannedLibraries = libraries.map { it.value }.sorted(),
                extrasFromMs = extrasBoundary(before.extrasFromMs, offsetFrom, newAccount, nowMs),
                replanFromMs = planned.replanFromMs(),
            )
        return planned to meta
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
    ): Long {
        val offset = lineup?.utcOffsetMinutes ?: standardOffsetMinutes()
        val server = lineup?.serverId ?: NO_SERVER
        val recipe = catalog.leftovers.firstOrNull() ?: LeftoversRecipe(STAND_IN_KEY, STAND_IN_KEY, "", 0, null)
        val settings = RecipeResolver.leftoversSettings(recipe, server, offset)
        return Epochs.epochStart(settings, Epochs.epochNumber(settings, nowMs) + 1)
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
                val repairs = savedWithRecord(owner, entry, lineup, updated, held?.repairs ?: start.repairs)
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
        return entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.writable = start.save
            val held = reconciled(entry, start)
            val lineup = held?.lineup ?: start.current
            if (!entry.writable || lineup == null) {
                noteWritable(entry)
                return@withLock null
            }
            val outcome = applied(lineup, edit)
            when {
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
                    val trimmed = trimmed(edited.lineup, meta, held?.repairs ?: start.repairs, nowMs())
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
                    forgetDerived(owner.key)
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
     * `grown-up-hold-2` (ADR-0020, option C) this is normally zero; it remains as a guard, counted apart.
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
            store.forget(owner.key).also { forgetDerived(owner.key) }
        }
    }

    // The days built from the lineup go after it: the days' own store and what was registered with [alsoForget],
    // never an AccountCache that could reach the library, and a fault there (none throws, but) cannot undo the
    // lineup's delete or reset. Cancellation goes through.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun forgetDerived(account: LibraryAccount) {
        try {
            forgetters.forEach { it(account) }
            seams.derived?.forget(account)
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

    /** [account]'s lineup as this service holds it now; null while it has none. */
    internal fun lineup(account: LibraryAccount): StateFlow<Revisioned?> =
        ownerOf(account)?.let { entryOf(it).state.asStateFlow() } ?: noLineup

    /**
     * Replaces [account]'s repair record (spec 26) with what [change] makes of the lineup as held now, under the
     * account's lock, saved with the lineup in one write and then published. The record is first rolled (what has
     * ended goes) and made to fit the file: [RepairConfig.fileMargin] stays free under the cap, the oldest fixes go
     * first when it does not fit, never one whose block is on air now, and at most [RepairConfig.maxFixes] stay. False
     * when there is no lineup, the file may not be written, the record cannot be made to fit, or the save failed or
     * was rejected: then nothing changed (not the file, not the published lineup or record). The first version-4
     * save keeps a copy of a version-3 file.
     *
     * A record the format cannot hold is refused at save time (false, nothing changed): a block that starts before
     * the one before it ends (a negative delta), a block of no length, a negative media start, an index outside an id
     * table, or two repairs of one channel day. Callers that build blocks must keep them in order and non-empty.
     */
    internal suspend fun recordRepairs(
        account: LibraryAccount,
        change: (Revisioned) -> TodayRepairs,
    ): Boolean {
        val owner = ownerOf(account) ?: return false
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
                false
            } else {
                record(owner, entry, held, start.fileVersion, change(held))
            }
        }
    }

    // [repairs] made to fit beside [lineup] for a save that has nothing to do with them: it never fails for their sake.
    private fun trimmed(
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
        nowMs: Long,
    ): Trimmed {
        val limit = LineupLimits.MAX_FILE_BYTES - seams.repairConfig.fileMargin
        return if (repairs.isEmpty) {
            Trimmed(repairs, 0, 0, 0)
        } else {
            repairs.trimmedToFit(nowMs, limit) { seams.encode(lineup, meta, it).size }
        }
    }

    // What was dropped is told to [LineupSeams.trimmed], as three counts and nothing else: only once it is saved.
    private fun told(trimmed: Trimmed) {
        if (trimmed.dropped > 0) seams.trimmed(trimmed.fixes, trimmed.coveringFixes, trimmed.selection)
    }

    // Saves [lineup] with its record where this session may write, and gives the record to publish with it: the one
    // that was saved (trimmed to fit), so memory equals disk. With no write, or a failed save, the record is kept as
    // it was.
    private suspend fun savedWithRecord(
        owner: Owner,
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
    ): TodayRepairs {
        if (!entry.writable) return repairs
        val trimmed = trimmed(lineup, meta, repairs, nowMs())
        val saved = saveVerified(owner, lineup, meta, trimmed.record)
        if (saved) told(trimmed)
        return if (saved) trimmed.record else repairs
    }

    private suspend fun record(
        owner: Owner,
        entry: Entry,
        held: Revisioned,
        fileVersion: Int?,
        wanted: TodayRepairs,
    ): Boolean {
        val nowMs = clock.now().toEpochMilliseconds()
        val limit = LineupLimits.MAX_FILE_BYTES - seams.repairConfig.fileMargin
        val fitted =
            wanted.rolled(nowMs).fitted(nowMs, seams.repairConfig, limit) {
                seams.encode(held.lineup, held.meta, it).size
            }
        return when {
            fitted == null -> {
                false
            }

            fitted == held.repairs -> {
                true
            }

            else -> {
                // Finishes even if the caller is cancelled, so the file and the published record never part.
                withContext(NonCancellable) {
                    // The first version-4 save of a version-3 file keeps the file as it stands (a failed copy is
                    // reported).
                    if (fileVersion == LineupCodec.BASE_VERSION && !fitted.isEmpty) keepUpgradeCopy(owner, fileVersion)
                    if (saveVerified(owner, held.lineup, held.meta, fitted)) {
                        entry.set(held.copy(repairs = fitted))
                        true
                    } else {
                        false
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
                        entry.writable = true
                        publish(entry, decoded.lineup, decoded.meta, decoded.repairs)
                    }
                    entry.loaded = stored != StoredBytes.Failed
                }
            }
        }
        return entry.state.value
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
        if (!entry.mutex.tryLock()) {
            entry.boundaries.rollSkipped(boundary)
            return
        }
        try {
            val held = entry.state.value
            val rolled = held?.lineup?.rolled(nowMs)
            val rolledRepairs = held?.repairs?.rolled(nowMs)
            // The roll never does the first version-3 write: a lineup decoded from a version 1 or 2 file has none of
            // the fields `rolled` clears (no leavesAtMs, previous rules or basis), so it comes back unchanged and is
            // not saved here; the first save of a new version is a refresh's or an edit's, which keep the old file's
            // copy first. It relies on `entry.writable` as the last `startOf` under the lock set it: this roll reads
            // nothing from the store and never sets it.
            // The repair record is pruned with it: a day that has ended is no use to anyone.
            val changed = held != null && (rolled !== held.lineup || rolledRepairs !== held.repairs)
            val due = held != null && changed && held.writable && entry.writable
            val trimmed = rolledRepairs?.takeIf { due }?.let { trimmed(checkNotNull(rolled), held.meta, it, nowMs) }
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

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> {
        val owner = ownerOf(account) ?: return flowOf(true)
        return flow {
            loaded(account)
            emitAll(entryOf(owner).state)
        }.map { held -> held?.lineup?.useMyCollections ?: true }.distinctUntilChanged()
    }

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
                Start(session, sessionMeta, save = true, isNew = session == null, repairs = sessionRepairs)
            }

            StoredBytes.Failed -> {
                report(LineupEvent.ReadFailedLeftAlone)
                Start(session, sessionMeta, save = false, repairs = sessionRepairs)
            }

            is StoredBytes.Present -> {
                startOf(owner, LineupCodec.decode(stored.bytes), Held(session, sessionMeta, sessionRepairs))
            }
        }
    }

    private suspend fun startOf(
        owner: Owner,
        decoded: LineupCodec.Decoded,
        session: Held,
    ): Start =
        when (decoded) {
            is LineupCodec.Decoded.Loaded -> {
                val lineup = decoded.lineup
                if (owner.owns(lineup)) {
                    // Only an older file's upgrade to version 3 is kept here; the first version-4 save keeps its own.
                    if (decoded.fileVersion < LineupCodec.BASE_VERSION) keepUpgradeCopy(owner, decoded.fileVersion)
                    Start(lineup, decoded.meta, true, repairs = decoded.repairs, fileVersion = decoded.fileVersion)
                } else {
                    setAside(owner, session)
                }
            }

            is LineupCodec.Decoded.NewerVersion -> {
                report(LineupEvent.NewerVersionLeftAlone)
                Start(session.lineup, session.meta, save = false, repairs = session.repairs)
            }

            LineupCodec.Decoded.Unreadable -> {
                setAside(owner, session)
            }
        }

    // The file of an older version is copied once before the first save of this version over it, so a downgrade has
    // something to go back to. A copy that cannot be written is reported and the save goes on: the lineup in memory
    // is what the viewer sees, and withholding every edit for the sake of a copy would be worse.
    private suspend fun keepUpgradeCopy(
        owner: Owner,
        fileVersion: Int,
    ) {
        if (!store.keepCopy(owner.key, "v$fileVersion")) report(LineupEvent.BackupFailed)
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
        session: Held,
    ): Start =
        if (store.setAside(owner.key)) {
            report(LineupEvent.SetAsideUnreadable)
            Start(null, RefreshMeta(), save = true)
        } else {
            report(LineupEvent.UnreadableLeftAlone)
            Start(session.lineup, session.meta, save = false, repairs = session.repairs)
        }

    // A lineup is saved only when its own bytes decode back to a lineup of this account; otherwise the stored file is
    // left as it is, and the in-memory lineup is still published.
    private suspend fun saveVerified(
        owner: Owner,
        planned: Lineup,
        meta: RefreshMeta,
        repairs: TodayRepairs,
    ): Boolean {
        val bytes = seams.encode(planned, meta, repairs)
        val back = LineupCodec.decode(bytes)
        return if (back !is LineupCodec.Decoded.Loaded || !owner.owns(back.lineup) || back.repairs != repairs) {
            report(LineupEvent.SaveRejected)
            false
        } else if (!store.save(owner.key, bytes)) {
            report(LineupEvent.SaveFailed)
            false
        } else {
            true
        }
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

private const val STAND_IN_KEY = "boundary"
