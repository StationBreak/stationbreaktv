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
 * Catalog 32 (#1013): Docs & Dusters (Drama 236) airs the Documentary genre or the `biography` tag, minus the kids
 * genres and anime. A biography-tagged title with no Documentary genre has a path; nothing else newly qualifies (the
 * old Documentary filter let in exactly the Documentary genre). Continental Drift (World 424) drops two studios.
 * The library is synthetic.
 */
class DocsAndDustersPoolTest {
    private val library = LibraryBuilder(32)
    private val documentaries =
        (1..8).map { library.film(TitleSpec(listOf("Documentary"), emptyList(), emptyList(), "PG", 90)) }
    private val historyDoc =
        library.film(TitleSpec(listOf("Documentary", "History"), listOf("history"), emptyList(), "PG-13", 95))
    private val biographyDrama =
        library.film(TitleSpec(listOf("Drama"), listOf("biography"), emptyList(), "PG-13", 120))
    private val biographyDoc =
        library.film(TitleSpec(listOf("Documentary"), listOf("biography"), emptyList(), "PG-13", 100))
    private val biographyKids =
        library.film(TitleSpec(listOf("Drama", "Kids"), listOf("biography"), emptyList(), "G", 80))
    private val biographyAnime =
        library.film(TitleSpec(listOf("Drama", "Anime"), listOf("biography", "anime"), emptyList(), "TV-14", 90))
    private val plainDrama = library.film(TitleSpec(listOf("Drama"), emptyList(), emptyList(), "PG-13", 110))
    private val historyOnly = library.film(TitleSpec(listOf("History"), listOf("history"), emptyList(), "PG-13", 110))
    private val documentaryTagOnly =
        library.film(TitleSpec(listOf("Drama"), listOf("documentary"), emptyList(), "PG-13", 110))
    private val biographySeries =
        library.series(TitleSpec(listOf("Drama"), listOf("biography"), emptyList(), "TV-14", 45), 2, 8)

    private val dropped = listOf("Wild Bunch Pictures", "Constantin Film Produktion")
    private val kept = listOf("Canal+", "StudioCanal", "Gaumont", "Pathé", "Rai Cinema", "Toho Co.")
    private val fromDropped = dropped.map { studioFilm(it) }
    private val fromKept = kept.map { studioFilm(it) }

    private fun studioFilm(studio: String): String {
        val spec = TitleSpec(listOf("Drama"), emptyList(), listOf(studio), "PG-13", 110)
        return library.film(spec)
    }

    init {
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val lanes =
        AuditLanes(
            PlannedLineup.of(ExtrasFilter.filter(library.build()).snapshot, CollectionNames(emptyMap()), null),
        )

    private fun pool(key: String): Set<CanonicalId> {
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == key } ?: return emptySet()
        return lane.cand.indices.mapTo(HashSet()) { lanes.titleOf(lane, it).id }
    }

    private val docs = pool("docs-and-dusters")
    private val drift = pool("continental-drift")

    private fun inDocs(name: String) = library.titleIds.getValue(name) in docs

    @Test
    fun aBiographyTaggedTitleWithoutTheDocumentaryGenreIsInThePool() {
        assertTrue(inDocs(biographyDrama), "a biography-tagged Drama is out")
        assertTrue(inDocs(biographySeries), "a biography-tagged series is out")
    }

    @Test
    fun aDocumentaryGenreTitleStillIs() {
        for (name in documentaries + historyDoc + biographyDoc) assertTrue(inDocs(name), "$name is out")
    }

    @Test
    fun nothingElseNewlyQualifies() {
        val out = listOf(biographyKids, biographyAnime, plainDrama, historyOnly, documentaryTagOnly)
        for (name in out) assertFalse(inDocs(name), "$name is in")
    }

    @Test
    fun noKidsTierTitleIsInThePool() {
        val lane = lanes.lanes.single { it.channel.recipe.key == "docs-and-dusters" }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    @Test
    fun aBiographyTaggedNonDocumentaryFilmCanHomeOnTheChannel() {
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles
        val home = homes.single { it.name == biographyDrama }.home?.channel
        assertTrue(home == "docs-and-dusters" || home == "life-stories", "homed on $home")
        assertTrue(
            homes.count { it.home?.channel == "docs-and-dusters" } >= documentaries.size,
            "the channel is filled by the documentaries",
        )
    }

    @Test
    fun theDroppedStudiosNoLongerMatchAndEachKeptStudioStillDoes() {
        for (name in fromDropped) assertFalse(library.titleIds.getValue(name) in drift, "$name is on Continental Drift")
        for (name in fromKept) assertTrue(library.titleIds.getValue(name) in drift, "$name is not on Continental Drift")
        assertEquals(
            6,
            lanes.lanes
                .single { it.channel.recipe.key == "continental-drift" }
                .cand.size,
        )
    }
}
