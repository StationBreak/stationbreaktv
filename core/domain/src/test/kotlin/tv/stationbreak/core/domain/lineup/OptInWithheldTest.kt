// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** A switched-off themed recipe withholds its collection anchor's members from the leftovers. */
class OptInWithheldTest {
    private val boxSet = id(900)
    private val members = listOf(movie(1, "Drama"), movie(2, "Drama"))
    private val others = listOf(movie(3, "Comedy"), movie(4, "Comedy"))

    private val themed =
        Recipe(
            key = "themed-late",
            name = "Themed Late",
            personality = "Synthetic.",
            band = Band.Premium,
            number = 580,
            optIn = true,
            sources = listOf(Source.Genre("Nothing")),
            exclusions = emptyList(),
            maxRating = null,
            ordering = OrderingMode.Curated,
            collectionHints = emptyList(),
            theme =
                Theme(
                    listOf(Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set"))),
                    emptyList(),
                    null,
                    false,
                ),
        )

    private val catalog =
        object : StarterCatalogView by TestCatalog {
            override val recipes = TestCatalog.recipes + themed
        }

    @Test
    fun aSwitchedOffThemedRecipeWithholdsItsCollectionAnchorMembers() {
        val odds = leftovers("odds-and-ends", 199, null)
        val lineup = lineupOf(odds)
        val days = LineupDays(catalog)
        val scheduled = days.scheduled(lineup, NOON.toEpochMilliseconds(), null)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                val collection =
                    RuleCollection(
                        boxSet,
                        members
                            .map { m ->
                                m.item.id
                            }.toSet(),
                        emptySet(),
                        emptySet(),
                        emptySet(),
                        "Box Set 1 Collection",
                    )
                val all = (members + others).sortedBy { item -> item.item.id.value }
                RuleSnapshot(it, setOf(LIBRARY_ID), all, emptyMap(), mapOf(boxSet to collection), true)
            }

        val built = days.days(lineup, scheduled, snapshots).single()

        val aired: Set<CanonicalId> = built.pool.mapTo(HashSet()) { it.id }
        assertEquals(others.map { it.item.id }.toSet(), aired)
    }
}
