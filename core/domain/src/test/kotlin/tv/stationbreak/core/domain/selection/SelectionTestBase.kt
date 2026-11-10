// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.SERVER
import tv.stationbreak.core.domain.lineup.USER
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.repair.RepairTestBase
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/** One cache write the selection made, as [WatchedLibrary] saw it. */
internal data class Applied(
    val keep: Set<ItemId>,
    val addStamps: Map<ItemId, Long>,
    val holds: Map<ItemId, Long>,
)

/**
 * The rig's library as the selection reaches it: every [applySelection] is recorded, and the next [failApplies] of
 * them throw before writing anything, as a cache write that fails would.
 */
internal class WatchedLibrary(
    private val inner: LibraryRepository,
) : LibraryRepository by inner {
    val applied: MutableList<Applied> = Collections.synchronizedList(mutableListOf())
    val landed: MutableList<Pair<ItemId, Long>> = Collections.synchronizedList(mutableListOf())
    val reverted: MutableList<Pair<ItemId, Long>> = Collections.synchronizedList(mutableListOf())

    @Volatile var failApplies = 0

    /** Every [applySelection] asked for, whether it wrote or failed. */
    @Volatile var applyCalls = 0

    /** Runs inside each [applySelection], before it writes: a slow cache write. */
    @Volatile var beforeApply: suspend () -> Unit = {}

    /** Runs at each [storedLibraries], before it reads. */
    @Volatile var beforeRows: () -> Unit = {}

    override suspend fun storedLibraries(account: LibraryAccount): List<StoredLibrary> {
        beforeRows()
        return inner.storedLibraries(account)
    }

    /** Runs inside each [revertUnproven], before it writes: the add pass racing the selection. */
    @Volatile var beforeRevert: suspend (ItemId, Long) -> Unit = { _, _ -> }

    /** Runs inside each landing, before it writes. */
    @Volatile var onLand: () -> Unit = {}

    /** The next [refuseLands] landings give false and land nothing, as one that lost a race would. */
    @Volatile var refuseLands = 0

    override suspend fun applySelection(
        account: LibraryAccount,
        keep: Set<ItemId>,
        addStamps: Map<ItemId, Long>,
        holds: Map<ItemId, Long>,
        nowMs: Long,
    ) {
        applyCalls++
        beforeApply()
        if (failApplies > 0) {
            failApplies--
            error("the cache write failed")
        }
        inner.applySelection(account, keep, addStamps, holds, nowMs)
        applied += Applied(keep, addStamps, holds)
    }

    override suspend fun landAdd(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): Boolean {
        onLand()
        if (refuseLands > 0) {
            refuseLands--
            return false
        }
        return inner.landAdd(account, library, stamp).also { if (it) landed += library to stamp }
    }

    override suspend fun revertUnproven(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): Boolean {
        beforeRevert(library, stamp)
        return inner.revertUnproven(account, library, stamp).also { if (it) reverted += library to stamp }
    }
}

/**
 * The settings as the selection reaches them: [beforeSave] runs inside each [saveSelection], before the write,
 * [failSaves] makes the next saves return false without writing, and [falseAfterWriting] makes the next saves write
 * and still return false (a write that landed, reported as failed).
 */
internal class WatchedSettings(
    val inner: InMemoryServerSettings,
) : SelectionStore by inner {
    @Volatile var beforeSave: suspend (StoredSelection) -> Unit = {}

    @Volatile var failSaves = 0

    @Volatile var falseAfterWriting = 0

    /** The next [failClears] clears return false and drop nothing. */
    @Volatile var failClears = 0

    override suspend fun clearLibraries(): Boolean {
        if (failClears > 0) {
            failClears--
            return false
        }
        return inner.clearLibraries()
    }

    /** The next [failLoads] reads throw, as settings that could not be read. */
    @Volatile var failLoads = 0

    /** How many times the selection was read. */
    val loads = AtomicInteger()

    /** The read whose number ([loads] after it) this is gives no selection, as one that could not be read. */
    @Volatile var unreadableLoad = 0

    override suspend fun loadSelection(): StoredSelection? {
        val load = loads.incrementAndGet()
        if (failLoads > 0) {
            failLoads--
            error("the settings could not be read")
        }
        return if (load == unreadableLoad) null else inner.loadSelection()
    }

    override suspend fun saveSelection(
        selection: StoredSelection,
        setup: Boolean,
    ): Boolean {
        beforeSave(selection)
        val written =
            if (failSaves > 0) {
                failSaves--
                false
            } else {
                inner.saveSelection(selection, setup)
            }
        val reported = !(written && falseAfterWriting > 0)
        if (!reported) falseAfterWriting--
        return written && reported
    }
}

/** The shared rig of the selection's tests (spec 26, Part 4 §2): a TV whose selection is saved in its settings. */
internal open class SelectionTestBase : RepairTestBase() {
    protected val address = ServerAddress(Scheme.Https, "server.example.test", 8920)
    protected val info = ServerInfo("Synthetic", ServerId(SERVER.value), ServerVersion(12, 1, 0))
    protected val session = Session(UserId(USER.value), AccessToken("synthetic-token"), info, address, null, true)
    protected val libE: ItemId = ItemId("e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3")
    protected val other = LibraryAccount(ServerId(SERVER.value), UserId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))

    /** The cache rows of [chosen]: A's comedies and B's; any other library starts empty. */
    protected fun itemsOf(chosen: Set<ItemId>): List<RuleItem> =
        (if (libA in chosen) comedies else emptyList()) + (if (libB in chosen) comediesB else emptyList())

    protected fun listed(ids: Set<ItemId>): ServerResult<List<Library>> =
        ServerResult.Success(ids.map { Library(it, "Synthetic", LibraryKind.Movies) })

    /**
     * One TV: [stored] saved in its settings (and [chosen] given its cache rows, with no stamp, fetched), a lineup of
     * [lineup] over [items], and a selection over all of it at [nowMs].
     */
    @Suppress("LongParameterList") // A TV's setup, one for one; all defaulted.
    protected inner class Tv(
        lineup: Lineup = lineupOf(laughs, ownB),
        chosen: Set<ItemId> = setOf(libA, libB),
        items: List<RuleItem> = itemsOf(chosen),
        stored: StoredSelection? = StoredSelection(chosen, seq = 1L),
        var nowMs: Long = atMs,
        private val config: SelectionConfig = SelectionConfig(),
    ) {
        val settings = WatchedSettings(InMemoryServerSettings(ServerRecord(address)))
        val rig: Rig = Rig(lineup, items, chosen, settings = settings)
        val library = WatchedLibrary(rig.library)
        val fake get() = rig.library.delegate
        val server = FakeMediaServer()
        val firstSync = InMemoryFirstSync()
        var restarts = 0

        /** When set, the add pass's restart throws. */
        @Volatile var failRestart = false
        var stayCurrentRequests = 0
        var signedIn: Session? = session
        val events: MutableList<SelectionEvent> = Collections.synchronizedList(mutableListOf())

        /** How long each resume at start took, as the selection reports it. */
        val resumeTimes: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        /** How long each change took, by step, as the selection reports it. */
        val changeTimes: MutableList<ChangeTiming> = Collections.synchronizedList(mutableListOf())

        /** The selection's clock for its timings: it moves only when a test moves it. */
        val ticks = TestTimeSource()

        /** The gate this TV's selection opens at its resume, as the app binds it for `SelectionReady`. */
        val gate = ResumeGate()
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
            }
        val selection =
            LibrarySelection(
                settings,
                library,
                server,
                { signedIn },
                rig.repairer,
                rig.service,
                rig.source,
                {
                    check(!failRestart) { "the restart could not be asked for" }
                    restarts++
                },
                firstSync,
                { stayCurrentRequests++ },
                gate,
                config,
                clock,
                { resumeTimes += it },
                { changeTimes += it },
                ticks = ticks,
            ) { events += it }

        init {
            runBlocking {
                if (stored != null) assertTrue(settings.inner.saveSelection(stored, setup = true))
                // A chosen library with rows is fetched in the fake (its rows are there before it is kept).
                if (chosen.isNotEmpty()) fake.applySelection(ACCOUNT, chosen, nowMs = nowMs)
            }
        }

        /** Writes [items] into the cache, as a walk of their library would (its row must be kept already). */
        fun seedItems(items: List<RuleItem>) {
            rig.library.seed(items)
            for (item in items) {
                val row = MediaItem(ItemId(item.item.id.value), "Synthetic", ItemKind.Movie, null, null)
                fake.seed(ACCOUNT, ItemId(item.libraryId.value), row)
            }
        }

        /** Another selection over the same stores: the same TV after its process was restarted. */
        fun restartedSelection(): LibrarySelection =
            LibrarySelection(
                settings,
                library,
                server,
                { signedIn },
                rig.repairer,
                rig.service,
                rig.source,
                {
                    check(!failRestart) { "the restart could not be asked for" }
                    restarts++
                },
                firstSync,
                { stayCurrentRequests++ },
                ResumeGate(),
                config,
                clock,
            ) { events += it }

        fun stored(): StoredSelection? = runBlocking { settings.loadSelection() }

        fun rows(): Map<ItemId, StoredLibrary> = runBlocking { fake.storedLibraries(ACCOUNT).associateBy { it.id } }

        fun row(id: ItemId): StoredLibrary? = rows()[id]

        /** Gives [id] the add stamp [stamp] in the cache, kept beside [keep], as a tick that was saved would. */
        fun stampInRoom(
            id: ItemId,
            stamp: Long,
            keep: Set<ItemId>,
        ) = runBlocking { fake.applySelection(ACCOUNT, keep + id, addStamps = mapOf(id to stamp), nowMs = nowMs) }

        fun resume() = runBlocking { selection.resumeAtStart() }

        fun change(ticked: Set<ItemId>): ChangeResult = runBlocking { selection.change(ticked) }

        fun listing(ids: Set<ItemId>): LibraryListing {
            server.librariesResult = listed(ids)
            return runBlocking { selection.listing().also { selection.followed() } }
        }

        /** Resumes, then lists [ids] once, so a tick of one of them is allowed. */
        fun ready(ids: Set<ItemId>): Tv {
            resume()
            listing(ids)
            return this
        }
    }
}
