// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog 18 (#1031): Little Spirits airs family anime by its `family anime` tag, its Ghibli and Miyazaki collections
 * and its theme studios, not by the bare Anime genre or tag, so a long PG series does not flood it. The library is
 * synthetic.
 */
class LittleSpiritsPoolTest {
    private val anime = listOf("Animation", "Anime")
    private val library = LibraryBuilder(11)
    private val mecha = library.series(TitleSpec(anime, listOf("anime", "mecha"), emptyList(), "PG", 24), 4, 26)
    private val studioFilm =
        library.film(TitleSpec(anime + "Fantasy", listOf("anime"), listOf("Studio Ghibli"), "PG", 110))
    private val ponocFilm =
        library.film(TitleSpec(anime + "Fantasy", listOf("anime"), listOf("Studio Ponoc"), "PG", 100))
    private val tagged = library.film(TitleSpec(anime, listOf("family anime"), emptyList(), "G", 95))
    private val teen = library.series(TitleSpec(anime, listOf("anime"), listOf("Studio Ghibli"), "TV-14", 24), 1, 12)
    private val barePg = library.film(TitleSpec(anime, listOf("anime"), emptyList(), "PG", 98))

    init {
        // Enough ordinary titles for the planner to fill a lineup.
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val poolIds: Set<CanonicalId>
    private val homesOnLittleSpirits: Set<String?>

    init {
        val snapshot = ExtrasFilter.filter(library.build()).snapshot
        val lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null))
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == KEY }
        poolIds = lane?.let { l -> l.cand.indices.mapTo(HashSet()) { lanes.titleOf(l, it).id } }.orEmpty()
        val report = LineupAudit.run(AuditInput(library.build(), null))
        homesOnLittleSpirits =
            report.titles
                .filter { it.home?.channel == KEY }
                .map { it.name }
                .toSet()
    }

    private fun inPool(name: String) = library.titleIds.getValue(name) in poolIds

    @Test
    fun aLongPgSeriesWithTheBareAnimeTagIsNotInTheShelfOrItsHomes() {
        for (name in listOf(mecha, barePg)) {
            assertFalse(inPool(name), "$name is in the pool")
            assertFalse(name in homesOnLittleSpirits, "$name is homed")
        }
    }

    @Test
    fun aGhibliStudioFilmWithNoCollectionAndAFamilyAnimeTagAreIn() {
        for (name in listOf(studioFilm, ponocFilm, tagged)) assertTrue(inPool(name), "$name is out")
        assertTrue(poolIds.isNotEmpty())
    }

    @Test
    fun aTeenRatedAnimeFromTheStudioStaysOut() {
        assertFalse(inPool(teen))
        assertFalse(teen in homesOnLittleSpirits)
    }

    private companion object {
        const val KEY = "little-spirits"
    }
}
