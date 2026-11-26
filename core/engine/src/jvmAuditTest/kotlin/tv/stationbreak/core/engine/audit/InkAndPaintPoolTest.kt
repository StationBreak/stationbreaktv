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
 * Catalog 23 (#1018): Ink & Paint (Family 54) airs the movies of Pixar, DreamWorks Animation and Walt Disney Animation
 * at PG / TV-PG and below. A look-alike studio, a bare Disney film, a series, an unrated or NR film, a Horror one and a
 * G film from a kids-tier studio (the kid channels' tier) stay out. The library is synthetic; the three studio names
 * are real studio names.
 */
class InkAndPaintPoolTest {
    private val library = LibraryBuilder(23)
    private val family = listOf("Animation", "Family")

    private fun film(
        studio: String,
        rating: String?,
        genres: List<String> = family,
    ) = library.film(TitleSpec(genres, emptyList(), listOf(studio), rating, 95))

    private val pixar = (1..14).map { film("Pixar", "PG") }
    private val dreamWorks = film("DreamWorks Animation", "PG")
    private val disney = film("Walt Disney Animation Studios", "PG")
    private val pixarLong = film("Pixar Animation Studios", "PG")

    // A G-rated animated family film is the kids tier (ADR-0028), which a Family-band channel never airs.
    private val pixarKidsTier = film("Pixar", "G")
    private val pixarTeen = film("Pixar", "PG-13")
    private val pixarUnrated = film("Pixar", null)
    private val pixarNr = film("Pixar", "NR")
    private val pixarHorror = film("Pixar", "PG", family + "Horror")
    private val lookAlike = film("Pixar Fan Films Ltd", "PG")
    private val skg = film("Not DreamWorks Animation SKG", "PG")
    private val bareDisney = film("Walt Disney Pictures", "PG", listOf("Family", "Comedy"))
    private val pixarSeries = library.series(TitleSpec(family, emptyList(), listOf("Pixar"), "TV-PG", 22), 2, 8)

    init {
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val lanes: AuditLanes
    private val poolIds: Set<CanonicalId>

    init {
        val snapshot = ExtrasFilter.filter(library.build()).snapshot
        lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null))
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == KEY }
        poolIds = lane?.let { l -> l.cand.indices.mapTo(HashSet()) { lanes.titleOf(l, it).id } }.orEmpty()
    }

    private fun inPool(name: String) = library.titleIds.getValue(name) in poolIds

    @Test
    fun aPgFilmFromEachOfTheThreeStudiosEnters() {
        for (name in pixar + dreamWorks + disney + pixarLong) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun overTheCeilingUnratedHorrorLookAlikeBareDisneyAndSeriesStayOut() {
        val out =
            listOf(pixarKidsTier, pixarTeen, pixarUnrated, pixarNr, pixarHorror) +
                listOf(lookAlike, skg, bareDisney, pixarSeries)
        for (name in out) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(54, lane.channel.recipe.number)
        assertEquals("PG", lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= pixar.size, "only ${homes.size} titles home on Ink & Paint")
    }

    private companion object {
        const val KEY = "ink-and-paint"
    }
}
