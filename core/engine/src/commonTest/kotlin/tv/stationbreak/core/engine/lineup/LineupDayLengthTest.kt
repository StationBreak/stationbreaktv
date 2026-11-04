// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.toSchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `LineupDay` takes the lineup's day to be [RecipeResolver.DAY_MS] long. Every starter and leftovers channel the
 * shipped catalog resolves to must have that epoch, so a catalog that changes a recipe's timing fails here, loudly.
 */
class LineupDayLengthTest {
    @Test
    fun everyResolvedStarterAndLeftoversChannelHasTheLineupsDayLength() {
        for (offset in listOf(0, -300, 330)) {
            for (recipe in StarterCatalog.recipes) {
                val rules = RecipeResolver.resolve(recipe, SERVER, offset, NO_NAMES, NO_LIBRARY)
                assertEquals(RecipeResolver.DAY_MS, rules.toSchedulerSettings().epochMs, recipe.key)
            }
            for (recipe in StarterCatalog.leftovers) {
                val settings = RecipeResolver.leftoversSettings(recipe, SERVER, offset)
                assertEquals(RecipeResolver.DAY_MS, settings.epochMs, recipe.key)
            }
        }
    }
}
