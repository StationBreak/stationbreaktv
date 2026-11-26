// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.Theme
import kotlin.test.Test
import kotlin.test.assertEquals

class SunriseStudioTest {
    private fun CuratedPool.titles() = items.mapTo(HashSet()) { it.groupId }

    // #1032: "Sunrise Productions" is not the anime studio; "Sunrise" in any case and its legal name still are.
    @Test
    fun aStudioThatOnlyStartsWithSunriseIsNotAnimeEvidence() {
        val lib = Library()
        val lookalike = lib.film(genres = listOf("Animation"), studios = listOf("Sunrise Productions", "Studio A"))
        val anime =
            listOf("Sunrise", "SUNRISE", "Sunrise Inc.").map {
                lib.film(genres = listOf("Animation"), studios = listOf(it))
            }
        val all = lib.itemsOf(listOf(lookalike) + anime)
        val family = channel("ch-f", 200, listOf("Animation"), all)
        val theme = Theme(emptyList(), emptyList(), null, animeOnly = true)
        val animeOnly = channel("ch-x", 300, listOf("Animation"), all, theme = theme)
        val snap = lib.snapshot()
        assertEquals(setOf(lookalike), Curation.pools(snap, listOf(family)).getValue(family.id).titles())
        assertEquals(anime.toSet(), Curation.pools(snap, listOf(animeOnly)).getValue(animeOnly.id).titles())
    }
}
