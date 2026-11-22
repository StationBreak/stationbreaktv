// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The band cap of a built-in channel moved into the Family or Teen band (#1078), as every reader takes it from
 * `ChannelCeilings.of` (#1088): the planner's scores, and a retired recipe's channel.
 */
class BandCapTest {
    private val catalog = TestCatalog(listOf(recipeOf("alpha", 310)))
    private val recipe = checkNotNull(catalog.recipes.single())

    private val family = movie(idOf(1), rating = "G", genres = listOf("alpha"))
    private val teen = movie(idOf(2), rating = "PG-13", genres = listOf("alpha"))
    private val adult = movie(idOf(3), rating = "R", genres = listOf("alpha"))
    private val snap = snapshot(items = listOf(family, teen, adult))

    // The planner's view of the unedited grown-up starter "alpha" (no ceiling of its own) at [number].
    private fun scoredPool(number: Int?): Set<CanonicalId> {
        val planning = Planning(SERVER, 0, snap, NO_NAMES, catalog, 1_000L, nextDayStart(1_000L))
        val moved =
            planning
                .starterChannel(recipe, 310, ChannelState.On, planning.resolve(recipe))
                .copy(numberOverride = number)
        return PlannerSelection.curationOf(moved, planning, household = true).pool.mapTo(HashSet()) { it.id }
    }

    @Test
    fun theStatsOfAStarterMovedIntoAYoungBandCountWhatItsDayAirs() {
        assertEquals(setOf(family.item.id, teen.item.id, adult.item.id), scoredPool(null), "at its own number")
        assertEquals(setOf(family.item.id), scoredPool(50), "the Family band")
        assertEquals(setOf(family.item.id, teen.item.id), scoredPool(80), "the Teen band")
    }

    private fun retired(
        id: String,
        number: Int?,
        maxRating: String? = null,
    ) = LineupChannel(
        ChannelId(id),
        null,
        number,
        null,
        ChannelState.On,
        ChannelSource.Custom(rules(listOf(Source.Genre("x")), maxRating = maxRating)),
        0L,
    )

    @Test
    fun aRetiredRecipesChannelKeepsTheBandCapOfABuiltInOne() {
        for (id in listOf("starter:old-grown-up", "leftovers:old-odds")) {
            assertEquals("PG", ChannelCeilings.of(retired(id, 50), catalog), "$id in Family")
            assertEquals("TV-14", ChannelCeilings.of(retired(id, 80), catalog), "$id in Teen")
            assertEquals("PG", ChannelCeilings.of(retired(id, 50, "R"), catalog), "$id above the cap")
            assertEquals("G", ChannelCeilings.of(retired(id, 80, "G"), catalog), "$id below it")
            assertEquals(null, ChannelCeilings.of(retired(id, 150), catalog), "$id outside the bands")
        }
    }

    @Test
    fun aViewersOwnCustomChannelKeepsNoHiddenCap() {
        assertEquals(null, ChannelCeilings.of(retired("custom:mine", 50), catalog))
        assertEquals("R", ChannelCeilings.of(retired("custom:mine", 80, "R"), catalog))
    }
}
