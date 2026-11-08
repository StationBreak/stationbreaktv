// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The recipe key a guide row finds a bundled logo by: a starter and a leftovers channel have one (spec 27). */
class ChannelMarkKeyTest {
    private fun LineupChannel.markKeyOf() = summary(TestCatalog, PlayKey("test"), NOON.toEpochMilliseconds()).markKey

    @Test
    fun aStarterSummaryHasItsRecipeKey() {
        val farm = starter("little-farm", 160, ChannelState.On, rules("Little Farm", Source.Genre("Animation")))
        assertEquals("little-farm", farm.markKeyOf())
    }

    @Test
    fun anEditedStarterSummaryStillHasItsRecipeKey() {
        val rules = rules("My Farm", Source.Genre("Animation"))
        val edited =
            channel(
                "starter-little-farm",
                160,
                ChannelState.On,
                ChannelSource.Starter("little-farm", edited = true, rules = rules),
            )
        assertEquals("little-farm", edited.markKeyOf())
    }

    @Test
    fun aLeftoversSummaryHasItsRecipeKey() {
        assertEquals("kids-odds-and-ends", leftovers("kids-odds-and-ends", 199, "G").markKeyOf())
    }

    @Test
    fun aCustomSummaryHasNoMarkKey() {
        val mine = channel("mine", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Crime"))))
        assertNull(mine.markKeyOf())
    }

    @Test
    fun anUnusableSummaryHasNoMarkKey() {
        val unreadable = channel("starter-little-farm", 160, ChannelState.On, ChannelSource.Unusable("{}"))
        val unreadableCustom =
            channel("mine", 600, ChannelState.On, ChannelSource.Unusable("""{"kind":"custom","rules":{}}"""))
        assertNull(unreadable.markKeyOf())
        assertNull(unreadableCustom.markKeyOf())
    }
}
