// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trade-off of catalog 5's Family exclusion on Cartoons After Dark (`StarterCatalog.VERSION`): a title that reaches
 * the channel by a tag or an anchor and carries Family was on it at catalog 4 and is off it now.
 */
class FamilyExclusionTradeOffTest {
    private val now = checkNotNull(StarterCatalog.recipe("cartoons-after-dark"))

    // The recipe as catalog 4 had it: its tags, and the Animation genre only as a filter, with no exclusion.
    private val before =
        now.copy(
            sources = now.sources.filterNot { it is Source.Genre },
            filters = now.filters?.copy(notGenres = null),
        )

    private fun titlesOf(
        recipe: Recipe,
        snapshot: RuleSnapshot,
    ): Set<CanonicalId> {
        val names = CollectionNames(snapshot.collections.mapNotNull { (id, c) -> c.name?.let { id to it } }.toMap())
        val server = CanonicalId.parse("0".repeat(SERVER_ZEROS) + "5")
        val rules = RecipeResolver.resolve(recipe, server, 0, names, LibraryNames(snapshot, names))
        return PoolEvaluator.evaluate(rules, snapshot, KidStatus.policyOf(recipe)).items.mapTo(HashSet()) { it.groupId }
    }

    @Test
    fun anAdultTaggedCartoonWithFamilyIsOffCartoonsAfterDarkNow() {
        val snapshot = ProfileLibraries.build("sitcom-household").input.snapshot
        val id = snapshot.series.entries.single { it.value.name == CaseTitles.ADULT_CARTOON_WITH_FAMILY }.key
        assertTrue(id in titlesOf(before, snapshot), "on at catalog 4")
        assertTrue(id !in titlesOf(now, snapshot), "off at catalog 5")
    }

    // The titles dropped by the Family exclusion, per profile library: on the channel by its tags or anchors at
    // catalog 4, off it now. Only the sitcom household carries one (the case above); the other libraries have no
    // adult-tagged cartoon that also carries Family.
    @Test
    fun theTitlesDroppedByTheFamilyExclusionPerProfile() {
        val dropped =
            ProfileLibraries.keys.associateWith { key ->
                val snapshot = ProfileLibraries.build(key).input.snapshot
                (titlesOf(before, snapshot) - titlesOf(now, snapshot)).size
            }
        val expected = ProfileLibraries.keys.associateWith { if (it == "sitcom-household") 1 else 0 }
        assertEquals(expected, dropped)
    }

    private companion object {
        const val SERVER_ZEROS = 31
    }
}
