// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A recipe with `adultSignalOnGenreEntry` (catalog 17, #1154) admits a title that comes in on its genre only with a
 * rating of PG-13 / TV-14 or higher. A title that reaches the channel by a tag source, a theme studio or an anchor is
 * admitted as before, and a recipe without the flag admits what it did.
 */
class AdultSignalTest {
    private val cartoon = listOf("Animation")
    private val studio = NameMatcher.Exact("Quillion Studios")
    private val theme = Theme(emptyList(), listOf(studio), null, false, adultSignalOnGenreEntry = true)

    private fun admitted(
        lib: Library,
        titles: List<CanonicalId>,
        theme: Theme = this.theme,
        anchors: List<Source> = emptyList(),
    ): Set<CanonicalId> {
        val pool = lib.itemsOf(titles)
        val ch =
            channel(
                "after-dark",
                516,
                cartoon,
                pool,
                anchors = anchors,
                theme = theme,
                extraSources = listOf(Source.Tag("adult animation")),
            )
        return Curation.lateOn(lib.snapshot(), ch, null).items.mapTo(HashSet()) { it.groupId }
    }

    @Test
    fun aKidRatedCartoonWithNoFamilyGenreIsOut() {
        val lib = Library()
        val g = lib.show(eps = 2, genres = cartoon, rating = "G")
        val y7 = lib.show(eps = 2, genres = cartoon, rating = "TV-Y7")
        val pg = lib.film(genres = cartoon, rating = "PG")
        assertEquals(emptySet(), admitted(lib, listOf(g, y7, pg)))
    }

    @Test
    fun anUnratedCartoonThatEntersByGenreIsOut() {
        val lib = Library()
        val unrated = lib.show(eps = 2, genres = cartoon)
        val approved = lib.film(genres = cartoon, rating = "Approved")
        assertEquals(emptySet(), admitted(lib, listOf(unrated, approved)))
    }

    @Test
    fun aTeenOrAdultRatedCartoonIsIn() {
        val lib = Library()
        val fourteen = lib.show(eps = 2, genres = cartoon, rating = "TV-14")
        val pg13 = lib.film(genres = cartoon, rating = "PG-13")
        val ma = lib.show(eps = 2, genres = cartoon, rating = "TV-MA")
        assertEquals(setOf(fourteen, pg13, ma), admitted(lib, listOf(fourteen, pg13, ma)))
    }

    @Test
    fun anAmbiguousRatingIsReadAtItsYoungestAgeAndIsNoSignal() {
        for (rating in listOf("C", "A", "11", "SE-11", "DK-11", "PG12", "JP-PG12", "AU-PG", "PG-13/11", "NR")) {
            val lib = Library()
            val film = lib.film(genres = cartoon, rating = rating)
            assertEquals(emptySet(), admitted(lib, listOf(film)), rating)
        }
    }

    @Test
    fun anAdultRatingIsASignalWhetherUsOrCountryPrefixed() {
        for (rating in listOf("PG-13", "PG13", "TV-14", "TV-14-V", "R", "TV-MA", "NC-17", "DE-16", "DE-18")) {
            val lib = Library()
            val film = lib.film(genres = cartoon, rating = rating)
            assertEquals(setOf(film), admitted(lib, listOf(film)), rating)
        }
    }

    @Test
    fun kidsEpisodesUnderAnAdultSeriesRatingStayOut() {
        val lib = Library()
        val mixed = lib.show(eps = 3, genres = cartoon, rating = "TV-14", epRatings = listOf("TV-Y", "TV-G", "TV-14"))
        assertEquals(emptySet(), admitted(lib, listOf(mixed)))
    }

    @Test
    fun aSeriesWithNoRatingAndOneAdultEpisodeStaysOut() {
        val lib = Library()
        val mixed = lib.show(eps = 3, genres = cartoon, epRatings = listOf("TV-Y", "TV-Y", "TV-14"))
        val allAdult = lib.show(eps = 3, genres = cartoon, epRatings = listOf("TV-14", "TV-MA"))
        val underAdultSeries = lib.show(eps = 2, genres = cartoon, rating = "TV-MA", epRatings = listOf(null))
        assertEquals(setOf(allAdult, underAdultSeries), admitted(lib, listOf(mixed, allAdult, underAdultSeries)))
    }

    @Test
    fun aTitleTheSnapshotDoesNotHoldIsNotAdmittedOnTheGenre() {
        val lib = Library()
        val known = lib.film(genres = cartoon, rating = "TV-MA")
        val unknown = lib.film(genres = cartoon, rating = "TV-MA")
        val partial = Library().also { it.film(genres = cartoon, rating = "TV-MA") }
        val pool = lib.itemsOf(listOf(known, unknown))
        val ch = channel("after-dark", 516, cartoon, pool, theme = theme)
        val out = Curation.lateOn(partial.snapshot(), ch, null).items.mapTo(HashSet()) { it.groupId }
        assertEquals(setOf(known), out)
    }

    @Test
    fun aTagStudioOrAnchorEntryNeedsNoRating() {
        val lib = Library()
        val tagged = lib.show(eps = 2, genres = cartoon, tags = listOf("Adult Animation"))
        val fromStudio = lib.show(eps = 2, genres = cartoon, studios = listOf("Quillion Studios"))
        val anchored = lib.film(genres = cartoon)
        val loose = lib.show(eps = 2, genres = cartoon)
        lib.collections += collection(idOf(1), movies = setOf(anchored))
        assertEquals(
            setOf(tagged, fromStudio, anchored),
            admitted(lib, listOf(tagged, fromStudio, anchored, loose), anchors = listOf(Source.Collection(idOf(1)))),
            "the tag, the studio and the anchor are in; the loose title is an unrated genre entry",
        )
    }

    @Test
    fun aRecipeWithoutTheFlagAdmitsTheKidRatedCartoon() {
        val lib = Library()
        val g = lib.show(eps = 2, genres = cartoon, rating = "G")
        val unrated = lib.film(genres = cartoon)
        val plain = Theme(emptyList(), listOf(studio), null, false)
        assertEquals(setOf(g, unrated), admitted(lib, listOf(g, unrated), theme = plain))
    }
}
