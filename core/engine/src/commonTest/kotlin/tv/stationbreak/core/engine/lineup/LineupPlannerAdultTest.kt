// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.rules.AdultMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val FILLERS = 48
private const val NOW = 12 * 3_600_000L
private val NEXT_DAY = nextDayStart(NOW)

/** After Hours stays adult when its recipe leaves the catalog and it becomes a custom channel (#984). */
class LineupPlannerAdultTest {
    private val ahKey = AdultMark.AFTER_HOURS_KEY
    private val fillers = List(FILLERS) { "r" + it.toString().padStart(2, '0') }
    private val catalog =
        TestCatalog(
            fillers.mapIndexed { i, key -> recipeOf(key, 120 + i) } +
                listOf(recipeOf(ahKey, 590, optIn = true), recipeOf("laughs", 110)),
        )
    private val snapshot = libraryOf((fillers + listOf(ahKey, "laughs")).associateWith { QUALIFYING_ITEMS })

    private fun withoutRecipe(key: String): Lineup {
        // Only a channel the viewer chose becomes custom; the rest of a removed recipe simply goes.
        val planned = planOf(null, snapshot, catalog, nowMs = NOW, boundaryMs = NEXT_DAY)
        val switchedOn = LineupEdit.SwitchOn(LineupIds.starter(key), confirmedOptIn = true)
        val applied = LineupEdits.apply(planned, switchedOn, NOW, NEXT_DAY, catalog)
        val first = assertIs<EditOutcome.Applied>(applied, applied.toString()).lineup
        val smaller = TestCatalog(catalog.recipes.filter { it.key != key }, version = 2)
        return planOf(first, snapshot, smaller, nowMs = NOW, boundaryMs = NEXT_DAY)
    }

    private fun customRules(
        lineup: Lineup,
        key: String,
    ) = assertIs<ChannelSource.Custom>(lineup.channel(key).source).rules

    @Test
    fun afterHoursLeavingTheCatalogBecomesAnAdultCustomChannel() {
        assertTrue(AdultMark.isMarked(customRules(withoutRecipe(ahKey), ahKey)))
    }

    @Test
    fun anotherStarterLeavingTheCatalogIsNotMarked() {
        assertFalse(AdultMark.isMarked(customRules(withoutRecipe("laughs"), "laughs")))
    }

    @Test
    fun theOnlyOptInRecipeOfTheShippedCatalogIsTheSharedAfterHoursKey() {
        assertEquals(listOf(AdultMark.AFTER_HOURS_KEY), StarterCatalog.recipes.filter { it.optIn }.map { it.key })
    }
}
