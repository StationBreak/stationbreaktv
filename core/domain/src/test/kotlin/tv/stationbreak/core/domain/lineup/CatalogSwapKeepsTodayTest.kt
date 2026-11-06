// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #596: the first catalog swap after an upgrade. The lineup was planned under catalog 2 and its days built with no
 * saved days (a format miss); a refresh then applies catalog 3 (changed rules, a new channel, selection by identity)
 * and the days are built again. Today's blocks of every channel that was already on the air must not move; the change
 * starts at the next day boundary (D1).
 */
class CatalogSwapKeepsTodayTest {
    private val hour = 3_600_000L
    private val now = NOON.toEpochMilliseconds() + 2 * hour
    private val noNames = CollectionNames(emptyMap())

    private fun catalog(
        version: Int,
        selects: Boolean,
        change: (Recipe) -> Recipe = { it },
        extra: List<Recipe> = emptyList(),
    ): StarterCatalogView =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = version
            override val selectsByIdentity: Boolean = selects
            override val recipes: List<Recipe> =
                (TestCatalog.recipes + extra).map { change(it.copy(ordering = OrderingMode.Curated)) }
        }

    private val v2 = catalog(2, selects = false)
    private val v3 =
        catalog(
            3,
            selects = true,
            change = {
                if (it.key == "laughs") it.copy(sources = listOf(Source.Genre("Comedy"), Source.Genre("Crime"))) else it
            },
            extra =
                listOf(
                    Recipe(
                        "crime-too",
                        "Crime Too",
                        "New in catalog 3.",
                        Band.Genre,
                        330,
                        optIn = false,
                        sources = listOf(Source.Genre("Crime"), Source.Genre("Comedy")),
                        exclusions = emptyList(),
                        maxRating = null,
                        ordering = OrderingMode.Curated,
                        collectionHints = emptyList(),
                    ),
                ),
        )

    private fun plan(
        lineup: Lineup?,
        atMs: Long,
        catalog: StarterCatalogView,
    ): Lineup =
        LineupPlanner.plan(
            lineup,
            SERVER,
            USER,
            0,
            CuratedLibrary.snapshotAt(atMs),
            noNames,
            atMs,
            boundaryMs,
            catalog,
        )

    private fun built(
        days: LineupDays,
        lineup: Lineup,
        atMs: Long,
    ) = days.curatedDays(lineup, atMs)

    @Test
    fun todayOfEveryExistingChannelSurvivesTheSwapAndTomorrowFollowsIt() {
        val first = plan(CuratedLibrary.lineup(), now - hour, v2)
        val served = built(LineupDays(v3), first, now)

        val swapped = plan(first, now, v3)
        val rebuilt = built(LineupDays(v3), swapped, now)

        assertTrue(served.isNotEmpty())
        for (day in served) {
            assertEquals(day.aired(), rebuilt.dayOf(day.channel.id.value).aired(), "today, ${day.channel.id}")
        }
        // Tomorrow takes the new catalog: the rolled lineup's days differ for at least one channel.
        val tomorrow = boundaryMs + hour
        val rolled = swapped.rolledAt(tomorrow)
        val after = built(LineupDays(v3), rolled, tomorrow)
        val before = built(LineupDays(v3), first.rolledAt(tomorrow), tomorrow)
        assertTrue(
            before.any { day -> day.aired() != after.dayOf(day.channel.id.value).aired() },
            "tomorrow follows catalog 3",
        )
    }

    private fun Lineup.rolledAt(atMs: Long) = rolled(atMs)
}
