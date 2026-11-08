// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The recipe key a guide row finds a bundled logo by: a starter and a leftovers channel have one (spec 27). */
class ChannelRecipeKeyTest {
    private fun LineupChannel.recipeKeyOf(): String? {
        val now = NOON.toEpochMilliseconds()
        return summary(TestCatalog, PlayKey("test"), now).recipeKey
    }

    @Test
    fun aStarterSummaryHasItsRecipeKey() {
        val farm = starter("little-farm", 160, ChannelState.On, rules("Little Farm", Source.Genre("Animation")))
        assertEquals("little-farm", farm.recipeKeyOf())
    }

    @Test
    fun anEditedStarterSummaryStillHasItsRecipeKey() {
        val rules = rules("My Farm", Source.Genre("Animation"))
        val edited =
            channel(
                LineupIds.starter("little-farm").value,
                160,
                ChannelState.On,
                ChannelSource.Starter("little-farm", edited = true, rules = rules),
            )
        assertEquals("little-farm", edited.recipeKeyOf())
    }

    /**
     * A starter whose recipe left the catalog is still a starter until the planner converts it to custom at a boundary,
     * and keeps its key: the guide must treat "a key with no logo" as normal.
     */
    @Test
    fun aStarterWhoseRecipeLeftTheCatalogStillHasItsRecipeKey() {
        val gone = starter("long-gone", 170, ChannelState.On, rules("Long Gone", Source.Genre("Western")))
        assertEquals("long-gone", gone.recipeKeyOf())
    }

    @Test
    fun aLeftoversSummaryHasItsRecipeKey() {
        assertEquals("kids-odds-and-ends", leftovers("kids-odds-and-ends", 199, "G").recipeKeyOf())
    }

    @Test
    fun aCustomSummaryHasNoRecipeKey() {
        val mine = channel("mine", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Crime"))))
        assertNull(mine.recipeKeyOf())
    }

    @Test
    fun anUnusableSummaryHasNoRecipeKey() {
        val farmId = LineupIds.starter("little-farm").value
        val unreadable = channel(farmId, 160, ChannelState.On, ChannelSource.Unusable("{}"))
        val unreadableCustom =
            channel("mine", 600, ChannelState.On, ChannelSource.Unusable("""{"kind":"custom","rules":{}}"""))
        assertNull(unreadable.recipeKeyOf())
        assertNull(unreadableCustom.recipeKeyOf())
    }
}
