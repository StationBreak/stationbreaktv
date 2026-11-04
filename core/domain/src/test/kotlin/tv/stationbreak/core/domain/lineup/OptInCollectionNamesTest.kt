// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class OptInCollectionNamesTest {
    private val server = CanonicalId.parse("0".repeat(31) + "1")
    private val boxSet = CanonicalId.parse("0".repeat(31) + "2")
    private val unnamed = CanonicalId.parse("0".repeat(31) + "3")

    private fun collection(
        id: CanonicalId,
        name: String?,
    ) = RuleCollection(id, emptySet(), emptySet(), emptySet(), emptySet(), name)

    @Test
    fun aThemedOptInRecipeResolvesCollectionAnchorsFromTheSnapshotNames() {
        val snapshot =
            RuleSnapshot(
                0L,
                emptySet(),
                emptyList(),
                emptyMap(),
                listOf(collection(boxSet, "Box Set 1 Collection"), collection(unnamed, null)).associateBy { it.id },
                true,
            )
        val names = collectionNamesOf(snapshot)
        assertEquals(CollectionNames(mapOf(boxSet to "Box Set 1 Collection")).ids, names.ids)
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set"))
        val recipe =
            Recipe(
                key = "themed",
                name = "Themed",
                personality = "Synthetic.",
                band = Band.General,
                number = 120,
                optIn = true,
                sources = listOf(Source.Genre("Comedy")),
                exclusions = emptyList(),
                maxRating = null,
                ordering = OrderingMode.Curated,
                collectionHints = emptyList(),
                theme = Theme(listOf(anchor), emptyList(), null, false),
            )
        val rules = RecipeResolver.resolve(recipe, server, 0, names, LibraryNames(snapshot, names))
        assertEquals(listOf(Source.Genre("Comedy"), Source.Collection(boxSet)), rules.sources)
    }
}
