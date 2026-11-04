// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

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
import tv.stationbreak.core.engine.lineup.rulesFor
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

/**
 * Today's curation is frozen (D1, Task 21 fix rounds 1 and 2): the rules of every starter today's curation reads stay
 * as they are until the boundary (keepers first; beyond the cap, a documented edge), and a themed refresh's switch-on
 * starts at the boundary (`onSinceMs`); ties follow the recipe's number in the planner and the day build alike.
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

    // A curated starter switched off at 14:00 holds no previous rules; a newer catalog's plan at 15:00 resolves it
    // again. It is still in the basis, so it is a keeper: today's rules stay, and so does everyone else's today.
    @Test
    fun aSwitchOffThenANewerCatalogLeavesTheRestOfTodayAlone() {
        val v1 = programmedCatalog(1)
        val v2 =
            programmedCatalog(2, change = {
                if (it.key == "laughs") it.copy(sources = listOf(Source.Genre("Comedy"), Source.Genre("Crime"))) else it
            })
        val days = LineupDays(v1)
        // The kids' leftovers is in the lineup already, so the newer catalog adds no channel that moves a policy. A
        // plan of the same catalog first sets the stored starters back to back (the old-file fix, at once).
        val kidsLeftovers = leftovers("kids-odds-and-ends", 99, "PG")
        val base = CuratedLibrary.lineup().let { it.copy(channels = it.channels + kidsLeftovers) }
        val planned = plan(base, noon + hour, v1)
        val switched =
            assertIs<EditOutcome.Applied>(
                LineupEdits.apply(
                    planned,
                    LineupEdit.SwitchOff(CuratedLibrary.laughsId),
                    noon + 2 * hour,
                    boundaryMs,
                    v1,
                ),
            ).lineup
        assertTrue(switched.curationBasis.let { it != null && CuratedLibrary.laughsId in it.on })

        val later = plan(switched, noon + 3 * hour, v2)

        val at = noon + 3 * hour
        val before = days.curatedDays(switched, at)
        val after = days.curatedDays(later, at)
        for (day in before) assertEquals(day.aired(), after.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        val keys = switched.keysAt(days, at)
        val laterKeys = later.keysAt(days, at)
        for (channel in switched.channels.filter { it.id != CuratedLibrary.laughsId }) {
            assertEquals(keys[channel.id], laterKeys[channel.id], "${channel.id}")
        }
    }

    @Test
    fun aReResolveBeyondTheCapTakesNewRulesAtOnce() {
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
        val planned =
            LineupPlanner.plan(lineup, SERVER, USER, 0, snapshot(noon, items), noNames, noon, boundaryMs, v2)

        // The documented edge of more than the cap on: a starter beyond the keepers takes its new rules at once (no
        // previous copy), so its today, and the homes it now wins, change in the middle of the day.
        val beyond =
            planned.channels
                .first { it.number == 300 + last }
                .source as ChannelSource.Starter
        assertNull(beyond.previousRules, "no previous copy beyond the cap")
        assertTrue(Source.Genre("Extra") in beyond.rulesFor(noon).sources)
        val today = built(lineup)
        val now = built(planned)
        val beyondId = "starter:${beyond.recipeKey}"
        assertNotEquals(today.dayOf(beyondId).aired(), now.dayOf(beyondId).aired())
        // A keeper the change does not touch keeps its day.
        val untouched = "starter:genre-5"
        assertEquals(today.dayOf(untouched).aired(), now.dayOf(untouched).aired())
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

    // The selecting catalog's first choice, then westerns back on the shelf (untouched), as an earlier plan left it.
    private fun withWesternsShelved(catalog: StarterCatalogView): Lineup {
        val library = fillingLibrary().sortedBy { it.item.id.value }
        val first =
            LineupPlanner.plan(null, SERVER, USER, 0, snapshot(noon, library), noNames, noon, boundaryMs, catalog)
        assertEquals(ChannelState.On, first.stateOf(westernsId))
        return first.copy(channels = first.channels.map { if (it.id == westernsId) it.shelved() else it })
    }

    private val westernsId = LineupIds.starter("westerns")

    // A refresh at 14:00 picks a shelved starter: it is on from the boundary. Today it has an empty day (out of the
    // guide), takes no part in today's curation, and every other channel's today and play key are unchanged.
    @Test
    fun aRefreshSelectsAStarterThatStartsAtTheBoundary() {
        val catalog = programmedCatalog(1, selects = true)
        val library = fillingLibrary().sortedBy { it.item.id.value }
        val shelved = withWesternsShelved(catalog)
        val at = noon + 2 * hour

        val planned =
            LineupPlanner.plan(shelved, SERVER, USER, 0, snapshot(at, library), noNames, at, boundaryMs, catalog)

        val westerns = planned.channels.first { it.id == westernsId }
        assertEquals(ChannelState.On, westerns.state)
        assertEquals(boundaryMs, westerns.onSinceMs)
        val days = LineupDays(catalog)

        fun built(of: Lineup): List<ChannelDay> {
            val scheduled = days.scheduled(of, at, null)
            val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshot(it, library) }
            return days.days(of, scheduled, snapshots)
        }
        val before = built(shelved)
        val after = built(planned)
        val day = after.dayOf(westernsId.value)
        assertTrue(day.pool.isEmpty())
        assertEquals(HiddenReason.EmptyToday, day.hidden, "out of the guide until it starts")
        for (other in before) {
            assertEquals(other.aired(), after.dayOf(other.channel.id.value).aired(), "${other.channel.id}")
        }
        val keys = shelved.keysAt(days, at)
        val plannedKeys = planned.keysAt(days, at)
        for (channel in shelved.channels.filter { it.id != westernsId }) {
            assertEquals(keys[channel.id], plannedKeys[channel.id], "${channel.id}")
        }
        assertTrue(planned.curationBasis.let { it != null && westernsId !in it.on }, "today's on-set is kept")
    }

    // From the boundary it airs as a full member of the curation: the same day as a starter that had long been on.
    @Test
    fun fromTheBoundaryTheStarterAirsAsAFullMember() {
        val catalog = programmedCatalog(1, selects = true)
        val library = fillingLibrary().sortedBy { it.item.id.value }
        val shelved = withWesternsShelved(catalog)
        val at = noon + 2 * hour
        val planned =
            LineupPlanner.plan(shelved, SERVER, USER, 0, snapshot(at, library), noNames, at, boundaryMs, catalog)
        val tomorrow = boundaryMs + hour
        val rolled = planned.rolled(tomorrow)
        val longOn =
            rolled.copy(
                channels = rolled.channels.map { if (it.id == westernsId) it.copy(onSinceMs = ADDED_MS) else it },
            )
        val days = LineupDays(catalog)

        fun built(of: Lineup): List<ChannelDay> {
            val scheduled = days.scheduled(of, tomorrow, null)
            val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshot(it, library) }
            return days.days(of, scheduled, snapshots)
        }
        val started = built(rolled).dayOf(westernsId.value)
        assertTrue(started.pool.isNotEmpty() && started.hidden == null)
        assertEquals(built(longOn).dayOf(westernsId.value).aired(), started.aired())
        assertTrue(started.weights.entries.any { it.second == 2 }, "its homes, weighted as homes")
    }

    @Test
    fun aFutureStartRoundTripsThroughTheLineupFile() {
        val catalog = programmedCatalog(1, selects = true)
        val library = fillingLibrary().sortedBy { it.item.id.value }
        val at = noon + 2 * hour
        val planned =
            LineupPlanner.plan(
                withWesternsShelved(catalog),
                SERVER,
                USER,
                0,
                snapshot(at, library),
                noNames,
                at,
                boundaryMs,
                catalog,
            )

        val decoded = LineupCodec.decode(LineupCodec.encode(planned)) as LineupCodec.Decoded.Loaded

        assertEquals(planned, decoded.lineup)
        assertEquals(
            boundaryMs,
            decoded.lineup.channels
                .first { it.id == westernsId }
                .onSinceMs,
        )
    }

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
