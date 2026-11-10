// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The household switch plans the lineup again, in one save (spec 23). */
class HouseholdReplanTest {
    // A starter that fills only through a household collection: its own genre holds no items.
    private val house =
        Recipe(
            "house",
            "House",
            "Synthetic.",
            Band.Genre,
            330,
            optIn = false,
            sources = listOf(Source.Genre("Unused")),
            exclusions = emptyList(),
            maxRating = null,
            ordering = null,
            collectionHints = listOf("Box Set 2"),
        )
    private val catalog =
        object : StarterCatalogView by TestCatalog {
            override val recipes: List<Recipe> = TestCatalog.recipes + house
        }

    private val boxSet = id(900)
    private val films = (810..816).map { movie(it, "Drama", rating = "G") }
    private val filmIds = films.map { it.item.id }.toSet()
    private val library =
        CountingLibrary().apply {
            seed(fillingLibrary() + films)
            delegate.seedCollection(
                ACCOUNT,
                RuleCollection(boxSet, filmIds, emptySet(), emptySet(), emptySet(), "Box Set 2"),
            )
            delegate.seedCollectionName(ACCOUNT, boxSet, "Box Set 2")
        }
    private val store = RecordingStore()
    private var nowMs = NOON.toEpochMilliseconds()
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, catalog)
    private val chosen: Set<ItemId>? = LIBRARIES
    private val manager = ChannelManager(service) { chosen }
    private val houseId = LineupIds.starter("house")
    private val westerns = LineupIds.starter("westerns")
    private val shelvedIds = setOf(houseId, westerns)

    private suspend fun houseState(): ChannelState? =
        checkNotNull(service.loaded(ACCOUNT))
            .lineup.channels
            .firstOrNull { it.id == houseId }
            ?.state

    // A lineup planned with the household's collections, then the state an earlier sync with them off would have left:
    // the flag off and the household-only starter on the shelf, and also a starter that has long filled (westerns), as
    // if a plan had not been made since.
    private suspend fun offAndShelved(): Lineup {
        service.refresh(ACCOUNT, LIBRARIES)
        val planned = checkNotNull(service.loaded(ACCOUNT)).lineup
        assertEquals(ChannelState.On, planned.channels.first { it.id == houseId }.state, "it fills with the flag on")
        val shelved =
            planned.copy(
                useMyCollections = false,
                channels = planned.channels.map { if (it.id in shelvedIds) it.shelved() else it },
            )
        store.inner.save(ACCOUNT, LineupCodec.encode(shelved))
        return shelved
    }

    private fun LineupChannel.shelved() = copy(state = ChannelState.Shelf)

    private fun Lineup.state(id: ChannelId) = channels.first { it.id == id }.state

    @Test
    fun theSwitchPlansAgainInTheSameSave() =
        runBlocking<Unit> {
            offAndShelved()
            val fresh = LineupService(store, library, clock, { 0 }, {}, catalog)

            assertEquals(ChangeResult.Done, ChannelManager(fresh) { LIBRARIES }.change(ACCOUNT, on))

            val lineup = checkNotNull(fresh.loaded(ACCOUNT)).lineup
            assertEquals(true, lineup.useMyCollections)
            assertEquals(ChannelState.On, lineup.state(westerns), "the plan ran: no sync was needed")
            // Today's day build still has no household collections, so the starter that needs them waits for tomorrow.
            assertEquals(ChannelState.Shelf, lineup.state(houseId))
            val bytes = checkNotNull(store.inner.peek(ACCOUNT))
            val saved = (LineupCodec.decode(bytes) as LineupCodec.Decoded.Loaded).lineup
            assertEquals(lineup, saved)
        }

    private val job = Job()
    private val scope = CoroutineScope(job)
    private val boundary = NOON.toEpochMilliseconds() + 18 * 3_600_000L // 06:00 the next day

    private fun refresherOver(service: LineupService) =
        LineupRefresher(service, library, { LIBRARIES }, NOT_WRITING, NO_CHANGES, report = {})

    private fun storedMeta(): RefreshMeta {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        return (LineupCodec.decode(bytes) as LineupCodec.Decoded.Loaded).meta
    }

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(10_000L) { while (!condition()) delay(10) }
    }

    // Switched on today, after an earlier sync with it off left the household-only starter on the shelf.
    private suspend fun switchedOnToday(): LineupService {
        library.delegate.useAccount(ACCOUNT)
        offAndShelved()
        val fresh = LineupService(store, library, clock, { 0 }, {}, catalog)
        ChannelManager(fresh) { LIBRARIES }.change(ACCOUNT, on)
        assertEquals(boundary, storedMeta().replanFromMs, "the pending change is remembered, in the file")
        return fresh
    }

    @Test
    fun theHouseholdOnlyStarterComesBackOnAtTheNextBoundaryThroughTheRefresher() =
        runBlocking<Unit> {
            val fresh = switchedOnToday()
            nowMs = boundary + 60_000L
            val reads = library.snapshotCutoffs.size

            refresherOver(fresh).start(scope)

            until { fresh.loaded(ACCOUNT)?.lineup?.state(houseId) == ChannelState.On }
            until { storedMeta().replanFromMs == null }
            assertTrue(library.snapshotCutoffs.size > reads, "the refresher planned, with the library as it was")
            assertEquals(ChannelState.On, fresh.loaded(ACCOUNT)?.lineup?.state(houseId))
            job.cancel()
        }

    @Test
    fun onlyAPlanClearsAPendingReplan() =
        runBlocking<Unit> {
            switchedOnToday()
            nowMs = boundary + 60_000L
            // The library cannot be read now, so the refresher's re-plan fails.
            val failing =
                object : LibraryRepository by library {
                    override suspend fun ruleSnapshot(
                        account: LibraryAccount,
                        libraries: Set<ItemId>,
                        cutoff: Instant,
                    ): RuleSnapshot = error("library down")

                    override suspend fun ruleSnapshots(
                        account: LibraryAccount,
                        selections: List<Set<ItemId>>,
                        cutoff: Instant,
                    ): List<RuleSnapshot> = error("library down")
                }
            val broken = LineupService(store, failing, clock, { 0 }, {}, catalog)
            LineupRefresher(broken, failing, { LIBRARIES }, NOT_WRITING, NO_CHANGES, report = {})
                .start(scope)
            delay(500)
            assertEquals(ChannelState.Shelf, broken.loaded(ACCOUNT)?.lineup?.state(houseId), "the re-plan failed")

            // An ordinary edit after the boundary does not plan, so it must not clear the pending re-plan.
            val off = ChannelChange.SwitchOff(westerns)
            assertEquals(ChangeResult.Done, ChannelManager(broken) { null }.change(ACCOUNT, off))

            val meta = storedMeta()
            assertTrue(meta.replanDue(nowMs), "still due: $meta")
            // A working refresher is behind because of it, and plans.
            val fresh = LineupService(store, library, clock, { 0 }, {}, catalog)
            refresherOver(fresh).start(scope)
            until { fresh.loaded(ACCOUNT)?.lineup?.state(houseId) == ChannelState.On }
            until { storedMeta().replanFromMs == null }
            job.cancel()
        }

    @Test
    fun theBackgroundRollAsksTheRefresherToPlanAtTheBoundary() =
        runBlocking<Unit> {
            val fresh = switchedOnToday()
            refresherOver(fresh).start(scope)
            delay(300)
            assertEquals(ChannelState.Shelf, fresh.loaded(ACCOUNT)?.lineup?.state(houseId), "not before the boundary")

            nowMs = boundary + 60_000L
            fresh.rollSoon(ACCOUNT, checkNotNull(fresh.loaded(ACCOUNT)))

            until { fresh.loaded(ACCOUNT)?.lineup?.state(houseId) == ChannelState.On }
            assertEquals(null, storedMeta().replanFromMs)
            job.cancel()
        }

    @Test
    fun withoutKnownLibrariesOnlyTheEditIsSaved() =
        runBlocking<Unit> {
            offAndShelved()
            val fresh = LineupService(store, library, clock, { 0 }, {}, catalog)

            assertEquals(ChangeResult.Done, ChannelManager(fresh) { null }.change(ACCOUNT, on))

            val lineup = checkNotNull(fresh.loaded(ACCOUNT)).lineup
            assertEquals(true, lineup.useMyCollections)
            assertEquals(ChannelState.Shelf, lineup.state(westerns), "not planned again")
        }

    private val on = ChannelChange.UseMyCollections(true)

    @Test
    fun switchingOffKeepsAnOnStarterOnAndSaysWhyItIsEmptyLater() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.UseMyCollections(false)))

            // An On channel that empties stays On (the day build says why it is empty).
            assertEquals(ChannelState.On, houseState())
            assertEquals(false, checkNotNull(service.loaded(ACCOUNT)).lineup.useMyCollections)
        }

    private suspend fun held(): Lineup = checkNotNull(service.loaded(ACCOUNT)).lineup

    // A setting survives a plan: switched off before the first choice, it stays off through the re-plan and through
    // the first choice once the collections are in, which ranks under it.
    @Test
    fun turningItOffBeforeTheFirstChoiceSticksThroughThePlans() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)
            assertFalse(held().finalised)

            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.UseMyCollections(false)))
            assertFalse(held().useMyCollections, "kept by the re-plan of the switch")

            library.delegate.setCollectionsSynced(ACCOUNT, true)
            service.refresh(ACCOUNT, LIBRARIES)
            val chosen = held()
            assertTrue(chosen.finalised)
            assertFalse(chosen.useMyCollections)
            assertEquals(ChannelState.Shelf, chosen.state(houseId), "chosen with the household's collections off")
            val saved = (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded)
            assertFalse(saved.lineup.useMyCollections)
        }

    // A reset replaces the channels, not the settings: the flag is carried from the lineup it replaces, as the extras
    // boundary is, and applies at once (a reset is the viewer's).
    @Test
    fun aResetWithTheFlagOffKeepsItOff() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.UseMyCollections(false)))

            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))

            val reset = held()
            assertFalse(reset.useMyCollections)
            assertNull(reset.curationBasis)
            assertEquals(ChannelState.Shelf, reset.state(houseId), "planned with the household's collections off")
            // And on stays on.
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.UseMyCollections(true)))
            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))
            assertTrue(held().useMyCollections)
        }

    // Was a known effect of PR 4 (a starter whose switch-on a household change kept waiting is switched on by the
    // plan at the boundary, after the new day has begun, and what it airs left the leftovers' current day at once).
    // Task 21 (spec 23) fixes it for programmed starters: until the basis's boundary the leftovers claim from the
    // basis on-set, which the late switch-on is not in, so the leftovers' day stays whole and changes at the next
    // boundary. A channel that is not programmed still claims its day pool at once (F5).
    @Test
    fun aDeferredSwitchOnLeavesTheLeftoversCurrentDayAlone() {
        val programmed =
            object : StarterCatalogView by catalog {
                override val recipes: List<Recipe> = catalog.recipes.map { it.copy(ordering = OrderingMode.Curated) }
            }
        val names = CollectionNames(mapOf(boxSet to "Box Set 2"))

        fun snapshotAt(cutoff: Long) =
            RuleSnapshot(
                cutoff,
                setOf(LIBRARY_ID),
                fillingLibrary() + films,
                emptyMap(),
                mapOf(boxSet to RuleCollection(boxSet, filmIds, emptySet(), emptySet(), emptySet(), "Box Set 2")),
                collectionsSynced = true,
            )
        val noonMs = NOON.toEpochMilliseconds()
        val first = LineupPlanner.plan(null, SERVER, USER, 0, snapshotAt(noonMs), names, noonMs, boundary, programmed)
        assertEquals(ChannelState.On, first.state(houseId))
        // At the boundary: the flag is on again, the basis is past (rolled), and the starter still waits on the shelf.
        val waiting = first.copy(channels = first.channels.map { if (it.id == houseId) it.shelved() else it })
        val atMs = boundary + 60_000L
        val next = boundary + DAY_MS
        val planned = LineupPlanner.plan(waiting, SERVER, USER, 0, snapshotAt(atMs), names, atMs, next, programmed)
        assertEquals(ChannelState.On, planned.state(houseId))
        assertTrue(planned.curationBasis.let { it != null && houseId !in it.on }, "today's on-set is kept")

        val days = LineupDays(programmed)

        fun leftovers(
            lineup: Lineup,
            at: Long,
        ): Set<CanonicalId> {
            val scheduled = days.scheduled(lineup, at, null)
            val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshotAt(it) }
            return days
                .days(lineup, scheduled, snapshots)
                .filter { it.channel.source is ChannelSource.Leftovers }
                .flatMapTo(HashSet()) { day -> day.pool.map { it.id } }
        }
        assertTrue(leftovers(waiting, atMs).containsAll(filmIds), "before the plan the leftovers air the box set today")
        assertTrue(leftovers(planned, atMs).containsAll(filmIds), "after it, today's leftovers keep them")
        assertTrue(leftovers(planned.rolled(next), next + 60_000L).none { it in filmIds }, "from the next boundary not")
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

private val NOT_WRITING = SyncActivity { MutableStateFlow(false) }
