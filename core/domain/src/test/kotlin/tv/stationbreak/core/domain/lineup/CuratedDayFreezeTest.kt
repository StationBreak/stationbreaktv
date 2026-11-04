// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.domain.lineup.CuratedLibrary.channel
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Today's curation is frozen (D1, Task 21 fix round 1): the rules of every starter today's curation reads stay as they
 * are until the boundary, and a themed refresh switches nothing on before it; ties follow the recipe's number in the
 * planner and the day build alike.
 */
class CuratedDayFreezeTest {
    private val hour = 3_600_000L
    private val noon = NOON.toEpochMilliseconds()
    private val noNames = CollectionNames(emptyMap())

    private fun programmedCatalog(
        version: Int,
        selects: Boolean = false,
        change: (Recipe) -> Recipe = { it },
        extra: List<Recipe> = emptyList(),
    ): StarterCatalogView =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = version
            override val selectsByIdentity: Boolean = selects
            override val recipes: List<Recipe> =
                (TestCatalog.recipes + extra).map { change(it.copy(ordering = OrderingMode.Curated)) }
        }

    private fun plan(
        lineup: Lineup,
        atMs: Long,
        catalog: StarterCatalogView,
        snapshot: RuleSnapshot = CuratedLibrary.snapshotAt(atMs),
    ): Lineup = LineupPlanner.plan(lineup, SERVER, USER, 0, snapshot, noNames, atMs, boundaryMs, catalog)

    private fun Lineup.keysAt(
        days: LineupDays,
        atMs: Long,
    ) = days.playKeysOf(this, atMs)

    @Test
    fun aSwitchOffAfterAMorningReResolveLeavesTheRestOfTodayAlone() {
        val v1 = programmedCatalog(1)
        // Catalog 2: the comedy recipe takes crime too, so its new rules would move the crime films' homes.
        val v2 =
            programmedCatalog(2, change = {
                if (it.key == "laughs") it.copy(sources = listOf(Source.Genre("Comedy"), Source.Genre("Crime"))) else it
            })
        val days = LineupDays(v1)
        // 09:00, a sync: every starter is resolved again from catalog 2; today keeps the old rules.
        val morning = plan(CuratedLibrary.lineup(), noon - 3 * hour, v2)
        val laughs = morning.channel(CuratedLibrary.laughsId.value).source as ChannelSource.Starter
        assertTrue(laughs.previousRules != null, "today keeps the rules from before the sync")
        // 14:00 the viewer switches laughs off; 15:00 another plan.
        val switched =
            assertIs<EditOutcome.Applied>(
                LineupEdits.apply(
                    morning.rolled(noon + 2 * hour),
                    LineupEdit.SwitchOff(CuratedLibrary.laughsId),
                    noon + 2 * hour,
                    boundaryMs,
                    v2,
                ),
            ).lineup
        val later = plan(switched, noon + 3 * hour, v2)

        val at = noon + 3 * hour
        val before = days.curatedDays(switched, at)
        val after = days.curatedDays(later, at)
        assertEquals(before.map { it.channel.id }, after.map { it.channel.id })
        for (day in before) assertEquals(day.aired(), after.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        val keys = switched.keysAt(days, at)
        val laterKeys = later.keysAt(days, at)
        for (channel in switched.channels.filter { it.id != CuratedLibrary.laughsId }) {
            assertEquals(keys[channel.id], laterKeys[channel.id], "${channel.id}")
        }
        assertTrue((later.channel(CuratedLibrary.laughsId.value).source as ChannelSource.Starter).previousRules != null)
    }

    @Test
    fun aReResolveBeyondTheCapLeavesOtherChannelsTodayAlone() {
        val genres = (0 until MANY).map { "Genre $it" }
        val base = (0 until MANY).map { recipeOf(it, listOf(Source.Genre(genres[it]))) }
        val last = MANY - 1
        // Catalog 2: the highest-numbered starter is now about "Extra" and genre 0, and wins the shared films' homes.
        val changed = base.map { if (it.number == 300 + last) it.copy(sources = it.sources + genreSources) else it }
        val v1 = catalogOf(base, 1)
        val v2 = catalogOf(changed, 2)
        val films =
            genres.flatMapIndexed { g, genre -> (0 until 6).map { movie(3000 + g * 10 + it, genre, rating = "R") } }
        val shared = (0 until 4).map { movie(2900 + it, genres[0], "Extra", rating = "R") }
        val items = (films + shared + CuratedLibrary.documentary).sortedBy { it.item.id.value }
        val lineup =
            lineupOf(
                *base
                    .map {
                        val rules = programmed(it.name, *it.sources.toTypedArray())
                        starter(it.key, it.number, ChannelState.On, rules)
                    }.toTypedArray(),
                leftovers("odds-and-ends", 199, null),
            )
        val days = LineupDays(v1)

        fun built(of: Lineup): List<ChannelDay> {
            val scheduled = days.scheduled(of, noon, null)
            val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshot(it, items) }
            return days.days(of, scheduled, snapshots)
        }
        val plan =
            LineupPlanner.planned(lineup, SERVER, USER, 0, snapshot(noon, items), noNames, noon, boundaryMs, v2)

        assertTrue(plan.replanAtBoundary, "the starter beyond the cap takes its new rules at the boundary")
        val today = built(lineup)
        val now = built(plan.lineup)
        for (day in today) assertEquals(day.aired(), now.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        val beyond =
            plan.lineup.channels
                .first { it.number == 300 + last }
                .source as ChannelSource.Starter
        assertNull(beyond.previousRules, "no previous copy beyond the cap")
    }

    @Test
    fun aCuratedStartersPlayKeyMovesWhenAnotherMembersRulesForTodayChange() {
        val days = LineupDays(TestCatalog)
        val lineup = CuratedLibrary.lineup()
        val wider = programmed("Whodunit", Source.Genre("Crime"), Source.Genre("Western"))
        val changed =
            lineup.copy(
                channels =
                    lineup.channels.map {
                        val source = it.source
                        if (it.id == CuratedLibrary.whodunitId && source is ChannelSource.Starter) {
                            it.copy(source = source.withRulesNow(wider))
                        } else {
                            it
                        }
                    },
            )

        val laughs = CuratedLibrary.laughsId.value
        assertNotEquals(
            days.playKeyOf(lineup.channel(laughs), lineup, noon),
            days.playKeyOf(changed.channel(laughs), changed, noon),
        )
        assertEquals(
            days.playKeyOf(lineup.channel("custom-1"), lineup, noon),
            days.playKeyOf(changed.channel("custom-1"), changed, noon),
            "a channel that is not curated reads no curation",
        )
    }

    // Two starters about the same genre: every title homes on the one with the lower recipe number, however the
    // viewer numbers them, in the planner's homing and in the day build alike.
    @Test
    fun aRenumberedStartersTiesFollowTheRecipeNumber() {
        val twin =
            recipeOf(40, listOf(Source.Genre("Comedy"))).copy(key = "laughs-too", band = Band.General, number = 150)
        val catalog = programmedCatalog(1, selects = true, extra = listOf(twin))
        val library = fillingLibrary().sortedBy { it.item.id.value }
        val first =
            LineupPlanner.plan(null, SERVER, USER, 0, snapshot(noon, library), noNames, noon, boundaryMs, catalog)
        val twinId = LineupIds.starter("laughs-too")
        // The viewer switches the twin on and numbers it below the comedy starter.
        val renumbered =
            first.copy(
                channels =
                    first.channels.map {
                        if (it.id == twinId) {
                            it.copy(state = ChannelState.On, numberOverride = 105, chosenByViewer = true)
                        } else {
                            it
                        }
                    },
            )

        val planned =
            LineupPlanner.plan(renumbered, SERVER, USER, 0, snapshot(noon, library), noNames, noon, boundaryMs, catalog)

        val laughs = planned.channel(CuratedLibrary.laughsId.value)
        assertEquals(ChannelState.On, laughs.state)
        assertNull(laughs.leavesAtMs, "the planner homes the comedy on it, by its recipe number")
        val days = LineupDays(catalog)
        val scheduled = days.scheduled(planned, noon, null)
        val built =
            days.days(
                planned,
                scheduled,
                scheduled.map { it.cutoffMs }.distinct().associateWith { snapshot(it, library) },
            )
        val comedy = library.filter { "Comedy" in it.genres }.mapTo(HashSet()) { it.item.id }
        assertEquals(comedy, built.dayOf(CuratedLibrary.laughsId.value).ids(), "the day build homes it there too")
        assertTrue(built.dayOf(twinId.value).pool.isEmpty())
    }

    // The refresher path: a themed refresh that picks a shelved starter leaves it on the shelf, asks to plan again at
    // the boundary, and the plan the refresher makes then switches it on.
    @Test
    fun aThemedSwitchOnWaitsForTheBoundaryThroughTheRefresher() =
        runBlocking<Unit> {
            val catalog = programmedCatalog(1, selects = true)
            val library = CountingLibrary().apply { seed(fillingLibrary()) }
            library.delegate.useAccount(ACCOUNT)
            val store = RecordingStore()
            var nowMs = noon
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
                }
            LineupService(store, library, clock, { 0 }, {}, catalog).refresh(ACCOUNT, LIBRARIES)
            val westerns = LineupIds.starter("westerns")
            val chosen = (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded)
            assertEquals(ChannelState.On, chosen.lineup.stateOf(westerns))
            val channels = chosen.lineup.channels.map { if (it.id == westerns) it.shelved() else it }
            val shelved = chosen.lineup.copy(channels = channels)
            store.inner.save(ACCOUNT, LineupCodec.encode(shelved, chosen.meta))

            val fresh = LineupService(store, library, clock, { 0 }, {}, catalog)
            fresh.refresh(ACCOUNT, LIBRARIES)
            val waiting = fresh.loaded(ACCOUNT)?.lineup?.stateOf(westerns)
            assertEquals(ChannelState.Shelf, waiting, "not in the middle of a day")
            assertEquals(boundaryMs, storedMeta(store).replanFromMs, "it asks to plan again at the boundary")

            nowMs = boundaryMs + 60_000L
            val job = Job()
            LineupRefresher(fresh, library, { LIBRARIES }, SyncActivity { MutableStateFlow(false) }, report = {})
                .start(CoroutineScope(job))
            try {
                withTimeout(10_000L) {
                    while (fresh.loaded(ACCOUNT)?.lineup?.stateOf(westerns) != ChannelState.On) delay(10)
                    while (storedMeta(store).replanFromMs != null) delay(10)
                }
            } finally {
                job.cancel()
            }
            assertEquals(
                boundaryMs + 60_000L,
                fresh
                    .loaded(ACCOUNT)
                    ?.lineup
                    ?.channels
                    ?.first { it.id == westerns }
                    ?.onSinceMs,
            )
        }

    private fun storedMeta(store: RecordingStore): RefreshMeta =
        (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded).meta

    private fun Lineup.stateOf(id: tv.stationbreak.core.model.ChannelId) = channels.first { it.id == id }.state

    private fun LineupChannel.shelved() = copy(state = ChannelState.Shelf)

    private val genreSources = listOf(Source.Genre("Extra"), Source.Genre("Genre 0"))

    private fun recipeOf(
        g: Int,
        sources: List<Source>,
    ) = Recipe(
        "genre-$g",
        "Genre $g",
        "Synthetic.",
        Band.Genre,
        300 + g,
        optIn = false,
        sources = sources,
        exclusions = emptyList(),
        maxRating = null,
        ordering = OrderingMode.Curated,
        collectionHints = emptyList(),
    )

    private fun catalogOf(
        recipes: List<Recipe>,
        version: Int,
    ): StarterCatalogView =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = version
            override val recipes: List<Recipe> = recipes
        }

    private fun snapshot(
        cutoffMs: Long,
        items: List<RuleItem>,
    ) = RuleSnapshot(cutoffMs, setOf(LIBRARY_ID), items, emptyMap(), emptyMap(), collectionsSynced = true)

    private companion object {
        const val MANY = 52
    }
}
