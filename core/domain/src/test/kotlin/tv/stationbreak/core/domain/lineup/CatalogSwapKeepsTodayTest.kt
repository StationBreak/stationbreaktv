// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    private val v2 = programmedCatalog(2)
    private val v3 =
        programmedCatalog(
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

    @Test
    fun todayOfEveryExistingChannelSurvivesTheSwapAndTomorrowFollowsIt() {
        // The starting lineup is a v3 file with viewerChoicesKnown = true (the inference path for pre-v3 files is out
        // of scope here).
        val first = plan(CuratedLibrary.lineup(), now - hour, v2)
        val served = LineupDays(v3).curatedDays(first, now)

        val swapped = plan(first, now, v3)
        val rebuilt = LineupDays(v3).curatedDays(swapped, now)

        // Preconditions: the swap really changed the plan, so the comparison below cannot pass vacuously.
        assertNotEquals(first, swapped)
        val laughs = swapped.channels.first { it.id == CuratedLibrary.laughsId }.source as ChannelSource.Starter
        assertNotNull(laughs.previousRules, "today keeps the rules from before the swap")
        assertTrue(swapped.channels.any { it.id == LineupIds.starter("crime-too") }, "catalog 3's new channel")
        assertTrue(served.isNotEmpty())
        for (day in served) {
            assertTrue(day.day.blocks.isNotEmpty(), "today has blocks, ${day.channel.id}")
            assertEquals(day.aired(), rebuilt.dayOf(day.channel.id.value).aired(), "today, ${day.channel.id}")
        }
        // Tomorrow takes the new catalog: the rolled lineup's days differ for at least one channel.
        val tomorrow = boundaryMs + hour
        val after = LineupDays(v3).curatedDays(swapped.rolled(tomorrow), tomorrow)
        val before = LineupDays(v3).curatedDays(first.rolled(tomorrow), tomorrow)
        assertTrue(
            before.any { day -> day.aired() != after.dayOf(day.channel.id.value).aired() },
            "tomorrow follows catalog 3",
        )
    }
}
