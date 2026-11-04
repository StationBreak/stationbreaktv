// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
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
        LineupRefresher(service, library, { LIBRARIES }, SyncActivity { MutableStateFlow(false) }, report = {})

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
}
