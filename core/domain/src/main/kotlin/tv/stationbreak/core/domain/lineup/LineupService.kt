// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** A lineup with the revision it was published at. A new refresh gives a new revision, so it is a cache key. */
internal data class Revisioned(
    val revision: Long,
    val lineup: Lineup,
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
class LineupService internal constructor(
    private val store: LineupStore,
    private val library: LibraryRepository,
    private val clock: Clock,
    private val standardOffsetMinutes: () -> Int,
    private val report: (LineupEvent) -> Unit,
    internal val catalog: StarterCatalogView,
) : ChannelRepository {
    constructor(
        store: LineupStore,
        library: LibraryRepository,
        clock: Clock,
        standardOffsetMinutes: () -> Int,
        report: (LineupEvent) -> Unit,
    ) : this(store, library, clock, standardOffsetMinutes, report, StarterCatalog)

    private class Entry {
        val mutex = Mutex()
        val state = MutableStateFlow<Revisioned?>(null)

        /** True once the store was read (successfully) for this account. Written under [mutex]. */
        @Volatile var loaded = false
    }

    /** Where a refresh starts from, and whether its result may be saved. */
    private class Start(
        val current: Lineup?,
        val save: Boolean,
    )

    private val lock = Any()
    private val entries = HashMap<LibraryAccount, Entry>()
    private var revisions = 0L
    private val noLineup: StateFlow<Revisioned?> = MutableStateFlow<Revisioned?>(null).asStateFlow()

    /**
     * Brings [account]'s lineup up to date with the library's [libraries]: after a sync, or at start when it is
     * absent, unfinalised or behind the catalog. Never throws for stored data.
     */
    suspend fun refresh(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) {
        val owner = ownerOf(account) ?: return report(LineupEvent.NonCanonicalAccount)
        val entry = entryOf(owner)
        entry.mutex.withLock {
            val start = startOf(owner, entry)
            entry.loaded = true
            val now = clock.now()
            val snapshot = library.ruleSnapshot(account, libraries, now)
            val names = library.collectionNames(account)
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
                )
            if (start.save && !store.save(owner.key, LineupCodec.encode(planned))) report(LineupEvent.SaveFailed)
            publish(entry, planned)
        }
    }

    /**
     * How many items of the [libraries] could air in a switched-on channel's day at [at] and yet air nowhere: in no
     * channel's day, and not held back on purpose (an opt-in recipe's or a hidden channel's). A check that the catalog
     * does its job: zero while the leftovers channels are on. Zero while there is no lineup. Reads the library as the
     * days are built (one `ruleSnapshot` per distinct cut-off).
     */
    suspend fun airingNowhereCount(
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
                        publish(entry, decoded.lineup)
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
        val session = entry.state.value?.lineup
        return when (val stored = store.load(owner.key)) {
            StoredBytes.Absent -> {
                Start(session, save = true)
            }

            StoredBytes.Failed -> {
                report(LineupEvent.ReadFailedLeftAlone)
                Start(session, save = false)
            }

            is StoredBytes.Present -> {
                startOf(owner, LineupCodec.decode(stored.bytes), session)
            }
        }
    }

    private suspend fun startOf(
        owner: Owner,
        decoded: LineupCodec.Decoded,
        session: Lineup?,
    ): Start =
        when (decoded) {
            is LineupCodec.Decoded.Loaded -> {
                val lineup = decoded.lineup
                if (owner.owns(lineup)) Start(lineup, save = true) else setAside(owner, session)
            }

            is LineupCodec.Decoded.NewerVersion -> {
                report(LineupEvent.NewerVersionLeftAlone)
                Start(session, save = false)
            }

            LineupCodec.Decoded.Unreadable -> {
                setAside(owner, session)
            }
        }

    // An unreadable file is moved out of the way before anything is saved; one that won't move is never written over.
    private suspend fun setAside(
        owner: Owner,
        session: Lineup?,
    ): Start =
        if (store.setAside(owner.key)) {
            report(LineupEvent.SetAsideUnreadable)
            Start(null, save = true)
        } else {
            report(LineupEvent.UnreadableLeftAlone)
            Start(session, save = false)
        }

    private fun publish(
        entry: Entry,
        lineup: Lineup,
    ) {
        val revision = synchronized(lock) { ++revisions }
        entry.state.value = Revisioned(revision, lineup)
    }

    private fun entryOf(owner: Owner): Entry = synchronized(lock) { entries.getOrPut(owner.key) { Entry() } }
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
