// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

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
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A lineup with the revision it was published at, and the refresher's [meta] about it. A new refresh gives a new
 * revision, so it is a cache key; a change of [meta] alone keeps the revision, since no channel changed.
 */
internal data class Revisioned(
    val revision: Long,
    val lineup: Lineup,
    val meta: RefreshMeta = RefreshMeta(),
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
        derived: AccountCache = AccountCache.None,
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
            // Before the read, so a write that lands during it leaves the generation behind and is seen next time.
            val generation = library.contentGeneration(account)
            val pass = library.collectionPassCompleted(account).first()
            val now = clock.now()
            val snapshot = library.ruleSnapshot(account, libraries, now)
            val names = library.collectionNames(account)
            // Read now, whatever trigger asked: the lineup stops waiting for collections that are not coming.
            val giveUp = library.failedCollectionPasses(account) >= GIVE_UP_AFTER
            val planned =
                LineupPlanner.plan(
                    start.current,
                    owner.server,
                    owner.user,
                    standardOffsetMinutes(),
                    snapshot,
                    names,
                    now.toEpochMilliseconds(),
                    catalog,
                    withoutCollections = giveUp,
                )
            val taken = pass?.takeIf { snapshot.collectionsSynced }?.toEpochMilliseconds()
            val meta =
                RefreshMeta(
                    plannedGeneration = generation,
                    collectionPassActedOn = taken ?: start.meta.collectionPassActedOn,
                    plannedLibraries = libraries.map { it.value }.sorted(),
                )
            if (entry.writable) saveVerified(owner, planned, meta)
            publish(entry, planned, meta)
            // Only now: a refresh that fails before publishing (the planner throws) leaves the stored lineup to load().
            entry.loaded = true
        }
        // Only a refresh that ran to its publish is timed: load to publish, including any wait on the account's lock.
        seams.timed(began.elapsedNow().inWholeMilliseconds)
    }

    /**
     * Records [pass] as taken in by [account]'s lineup without planning again: the refresher found nothing in the
     * library to plan from since the lineup last took in a pass, so the lineup already reflects this one too. Without
     * this a pass that wrote nothing would look new after every restart. Only a lineup that already took in a pass is
     * touched. It changes the [RefreshMeta] and nothing else, so the revision does not move and the lineup saved is
     * the one this session holds, never an older one read back from the file. Never throws for stored data; saves under
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
            val held = entry.state.value
            val lineup = held?.lineup ?: start.current
            val meta = held?.meta ?: start.meta
            val ms = pass.toEpochMilliseconds()
            val acted = meta.collectionPassActedOn
            if (lineup != null && acted != null && acted != ms) {
                val updated = meta.copy(collectionPassActedOn = ms)
                if (entry.writable) saveVerified(owner, lineup, updated)
                if (held == null) publish(entry, lineup, updated) else entry.state.value = held.copy(meta = updated)
                entry.loaded = true
            }
        }
    }

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
            seams.derived.forget(owner.key)
            store.forget(owner.key)
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
    ) {
        val bytes = seams.encode(planned, meta)
        val back = LineupCodec.decode(bytes)
        if (back !is LineupCodec.Decoded.Loaded || !owner.owns(back.lineup)) {
            report(LineupEvent.SaveRejected)
        } else if (!store.save(owner.key, bytes)) {
            report(LineupEvent.SaveFailed)
        }
    }

    private fun publish(
        entry: Entry,
        lineup: Lineup,
        meta: RefreshMeta,
    ) {
        val revision = synchronized(lock) { ++revisions }
        entry.state.value = Revisioned(revision, lineup, meta)
    }

    private fun entryOf(owner: Owner): Entry = synchronized(lock) { entries.getOrPut(owner.key) { Entry() } }
}

/**
 * How many collection passes must have ended without completing before a lineup stops waiting for them: the first
 * lineup for its first choice, and a finalised one for a newer catalog (which it would otherwise never receive).
 */
internal const val GIVE_UP_AFTER = 2

/**
 * What a test can swap in the service: its catalog, how it encodes a lineup and its meta for the store, where
 * timings go, and what is forgotten with a lineup.
 */
internal class LineupSeams(
    val catalog: StarterCatalogView = StarterCatalog,
    val encode: (Lineup, RefreshMeta) -> ByteArray = LineupCodec::encode,
    val timed: (Long) -> Unit = {},
    val derived: AccountCache = AccountCache.None,
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
