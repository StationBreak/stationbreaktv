// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A lineup with the revision it was published at, and the refresher's [meta] about it. A new refresh gives a new
 * revision, so it is a cache key; a change of [meta] alone keeps the revision, since no channel changed. [writable]
 * is whether this session may write the account's file, as it stood when this was published (spec 21).
 */
internal data class Revisioned(
    val revision: Long,
    val lineup: Lineup,
    val meta: RefreshMeta = RefreshMeta(),
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
@Suppress("TooManyFunctions") // The one owner of the lineup in memory; its load, plan and save steps stay together.
class LineupService internal constructor(
    private val store: LineupStore,
    private val library: LibraryRepository,
    private val clock: Clock,
    private val standardOffsetMinutes: () -> Int,
    private val report: (LineupEvent) -> Unit,
    private val seams: LineupSeams,
) : ChannelRepository {
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
     * [derived] is told when a lineup is forgotten ([forget]): what was built from it (the days on disk, #480) goes
     * too.
     */
    @Suppress("LongParameterList") // The app's one constructor: the store, the reads and three numbers-only hooks.
    constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
        timed: (Long) -> Unit,
        derived: DayStore? = null,
    ) : this(store, library, clock, standardOffsetMinutes, report, LineupSeams(timed = timed, derived = derived))

    internal val catalog: StarterCatalogView get() = seams.catalog

    private class Entry {
        val mutex = Mutex()
        val state = MutableStateFlow<Revisioned?>(null)

        /** True once the store was read (successfully) for this account. Written under [mutex]. */
        @Volatile var loaded = false

        /**
         * Whether this session may write the account's file. False when the session started from a file that was
         * unreadable just now, from a newer app, or unreadable and could not be set aside. Set under [mutex] at the
         * start of each refresh, **every save path must check it**, and it is never true while a stored file stands
         * that this app must not write over.
         */
        @Volatile var writable = false
    }

    /** Where a refresh starts from, and whether its result may be saved. */
    private class Start(
        val current: Lineup?,
        val meta: RefreshMeta,
        val save: Boolean,
    )

    private val lock = Any()
    private val entries = HashMap<LibraryAccount, Entry>()

    // Process-global, across accounts: BuiltInScheduleSource's cache key is this revision, and relies on that for
    // account separation (two accounts never share a revision number).
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
            val (planned, meta) = planned(owner, account, libraries, start.current, start.meta.collectionPassActedOn)
            if (entry.writable) saveVerified(owner, planned, meta)
            publish(entry, planned, meta)
            // Only now: a refresh that fails before publishing (the planner throws) leaves the stored lineup to load().
            entry.loaded = true
        }
        // Only a refresh that ran to its publish is timed: load to publish, including any wait on the account's lock.
        seams.timed(began.elapsedNow().inWholeMilliseconds)
    }

    /**
     * The lineup planned from [current] (null: from nothing) against the library's [libraries] now, and the meta to
     * record with it: [passActedOn] is kept unless the planning read took in a newer collection pass. Reads as
     * documented on [refresh]. Call under the account's lock.
     */
    private suspend fun planned(
        owner: Owner,
        account: LibraryAccount,
        libraries: Set<ItemId>,
        current: Lineup?,
        passActedOn: Long?,
    ): Pair<Lineup, RefreshMeta> = plannedFrom(owner, readForPlan(account, libraries), libraries, current, passActedOn)

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
        val snapshot = library.ruleSnapshot(account, libraries, now)
        val names = library.collectionNames(account)
        // Read now, whatever trigger asked: the lineup stops waiting for collections that are not coming.
        val giveUp = library.failedCollectionPasses(account) >= GIVE_UP_AFTER
        return PlanRead(generation, pass, now, snapshot, names, giveUp)
    }

    private fun plannedFrom(
        owner: Owner,
        read: PlanRead,
        libraries: Set<ItemId>,
        current: Lineup?,
        passActedOn: Long?,
    ): Pair<Lineup, RefreshMeta> {
        val planned =
            LineupPlanner.plan(
                current,
                owner.server,
                owner.user,
                standardOffsetMinutes(),
                read.snapshot,
                read.names,
                read.now.toEpochMilliseconds(),
                catalog,
                withoutCollections = read.giveUp,
            )
        val taken = read.pass?.takeIf { read.snapshot.collectionsSynced }?.toEpochMilliseconds()
        val meta =
            RefreshMeta(
                plannedGeneration = read.generation,
                collectionPassActedOn = taken ?: passActedOn,
                plannedLibraries = libraries.map { it.value }.sorted(),
            )
        return planned to meta
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
                if (entry.writable) saveVerified(owner, lineup, updated)
                if (held == null) {
                    publish(entry, lineup, updated)
                } else {
                    entry.state.value = held.copy(meta = updated, writable = entry.writable)
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
     * refusal, publish nothing. [Saved.Failed]: the save failed or was rejected, and nothing changed.
     * [Saved.Invalid]: the edit would break the lineup's rules. Null: there is no lineup, or the file may not be
     * written (a newer app's, say): nothing changes.
     */
    internal suspend fun edit(
        account: LibraryAccount,
        edit: LineupEdit,
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
                    val meta = held?.meta ?: start.meta
                    // Published only once saved: a lineup that is not on disk would be undone by the next refresh.
                    if (saveVerified(owner, outcome.lineup, meta)) {
                        publish(entry, outcome.lineup, meta)
                        entry.loaded = true
                        Saved.Ok(outcome)
                    } else {
                        Saved.Failed
                    }
                }
            }
        }
    }

    // Null: the edit could not build a lineup that keeps the invariants (the edits are meant to make that impossible).
    @Suppress("SwallowedException")
    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): EditOutcome? =
        try {
            LineupEdits.apply(lineup, edit, clock.now().toEpochMilliseconds(), catalog)
        } catch (_: IllegalArgumentException) {
            report(LineupEvent.EditInvalid)
            null
        }

    /**
     * Replaces [account]'s lineup with a fresh first choice from the [libraries], under the account's lock. Like
     * [refresh] it may set an unreadable or foreign file aside first. The old lineup stays until the new one is
     * planned and saved; the save, the forgetting of what was built from the old one and the publishing then finish
     * even if the caller is cancelled, so the lineup is never left forgotten or half replaced. [Saved.Failed]: the
     * save failed or was rejected, and nothing changed. [Saved.Unavailable]: the library could not be read, and nothing
     * changed. Null: the
     * file may not be written, and nothing changes. Never throws for stored data; other failures (the planner's, say)
     * propagate.
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
            val (fresh, meta) = plannedFrom(owner, read, libraries, null, null)
            withContext(NonCancellable) {
                if (saveVerified(owner, fresh, meta)) {
                    forgetDerived(owner.key)
                    publish(entry, fresh, meta)
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
                publish(entry, start.current, start.meta)
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
        if (held != null && held.writable && !entry.writable) entry.state.value = held.copy(writable = false)
    }

    /** Reports [event] as the service's own diagnostics do. */
    internal fun reportEvent(event: LineupEvent) = report(event)

    /**
     * How many items of the [libraries] could air in a switched-on channel's day at [at] and yet air nowhere: in no
     * channel's day, and not held back on purpose (an opt-in recipe's or a hidden channel's). A check that the catalog
     * does its job: zero while the leftovers channels are on. Zero while there is no lineup. Reads the library as the
     * days are built (one `ruleSnapshot` per distinct cut-off).
     */
    internal suspend fun airingNowhereCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): Int {
        val lineup = loaded(account)?.lineup ?: return 0
        val days = LineupDays(catalog)
        val scheduled = days.scheduled(lineup, at.toEpochMilliseconds())
        val snapshots = snapshotsOf(library, account, libraries, scheduled.map { it.cutoffMs }.distinct().sorted())
        return days.airingNowhere(lineup, scheduled, snapshots)
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
            entry.state.value = null
            entry.loaded = false
            entry.writable = false
            store.forget(owner.key).also { forgetDerived(owner.key) }
        }
    }

    // The days built from the lineup go after it: only the days' own store, never an AccountCache that could reach the
    // library, and a fault there (it never throws, but) cannot undo the lineup's delete. Cancellation goes through.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun forgetDerived(account: LibraryAccount) {
        try {
            seams.derived?.forget(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort: the days are a cache, and their key no longer matches a lineup that is gone.
        }
    }

    /** [account]'s lineup as this service holds it now; null while it has none. */
    internal fun lineup(account: LibraryAccount): StateFlow<Revisioned?> =
        ownerOf(account)?.let { entryOf(it).state.asStateFlow() } ?: noLineup

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
                        publish(entry, decoded.lineup, decoded.meta)
                    }
                    entry.loaded = stored != StoredBytes.Failed
                }
            }
        }
        return entry.state.value
    }

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> {
        val owner = ownerOf(account) ?: return flowOf(emptyList())
        return flow {
            loaded(account)
            emitAll(entryOf(owner).state)
        }.map { held ->
            held
                ?.lineup
                ?.channels
                .orEmpty()
                .sortedWith(CHANNEL_ORDER)
                .map { it.summary(catalog) }
        }.distinctUntilChanged()
    }

    private suspend fun startOf(
        owner: Owner,
        entry: Entry,
    ): Start {
        val held = entry.state.value
        val session = held?.lineup
        val sessionMeta = held?.meta ?: RefreshMeta()
        return when (val stored = store.load(owner.key)) {
            StoredBytes.Absent -> {
                Start(session, sessionMeta, save = true)
            }

            StoredBytes.Failed -> {
                report(LineupEvent.ReadFailedLeftAlone)
                Start(session, sessionMeta, save = false)
            }

            is StoredBytes.Present -> {
                startOf(owner, LineupCodec.decode(stored.bytes), session, sessionMeta)
            }
        }
    }

    private suspend fun startOf(
        owner: Owner,
        decoded: LineupCodec.Decoded,
        session: Lineup?,
        sessionMeta: RefreshMeta,
    ): Start =
        when (decoded) {
            is LineupCodec.Decoded.Loaded -> {
                val lineup = decoded.lineup
                if (owner.owns(lineup)) {
                    Start(lineup, decoded.meta, save = true)
                } else {
                    setAside(owner, session, sessionMeta)
                }
            }

            is LineupCodec.Decoded.NewerVersion -> {
                report(LineupEvent.NewerVersionLeftAlone)
                Start(session, sessionMeta, save = false)
            }

            LineupCodec.Decoded.Unreadable -> {
                setAside(owner, session, sessionMeta)
            }
        }

    // An unreadable file is moved out of the way before anything is saved; one that won't move is never written over.
    private suspend fun setAside(
        owner: Owner,
        session: Lineup?,
        sessionMeta: RefreshMeta,
    ): Start =
        if (store.setAside(owner.key)) {
            report(LineupEvent.SetAsideUnreadable)
            Start(null, RefreshMeta(), save = true)
        } else {
            report(LineupEvent.UnreadableLeftAlone)
            Start(session, sessionMeta, save = false)
        }

    // A lineup is saved only when its own bytes decode back to a lineup of this account; otherwise the stored file is
    // left as it is, and the in-memory lineup is still published.
    private suspend fun saveVerified(
        owner: Owner,
        planned: Lineup,
        meta: RefreshMeta,
    ): Boolean {
        val bytes = seams.encode(planned, meta)
        val back = LineupCodec.decode(bytes)
        return if (back !is LineupCodec.Decoded.Loaded || !owner.owns(back.lineup)) {
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
    ) {
        val revision = synchronized(lock) { ++revisions }
        entry.state.value = Revisioned(revision, lineup, meta, entry.writable)
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

/**
 * What a test can swap in the service: its catalog, how it encodes a lineup and its meta for the store, where
 * timings go, and what is forgotten with a lineup.
 */
internal class LineupSeams(
    val catalog: StarterCatalogView = StarterCatalog,
    val encode: (Lineup, RefreshMeta) -> ByteArray = LineupCodec::encode,
    val timed: (Long) -> Unit = {},
    val derived: DayStore? = null,
)

/** An account in canonical form, as the store keys it, with its IDs for the planner. */
private class Owner(
    val key: LibraryAccount,
    val server: CanonicalId,
    val user: CanonicalId,
) {
    /** True when [lineup] is this account's. */
    fun owns(lineup: Lineup) = lineup.serverId == server && lineup.userId == user
}

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
