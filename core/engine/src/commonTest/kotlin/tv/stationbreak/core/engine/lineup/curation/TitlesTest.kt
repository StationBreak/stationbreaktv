// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.lineup.CurationTables
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitlesTest {
    // Studio names come from the tables, so no real name is written in a fixture.
    private val animeStudio = CurationTables.ANIME_STUDIOS.first().folded
    private val adultNetwork = CurationTables.ADULT_ANIMATION_NETWORKS.first().folded

    private fun titlesOf(
        items: List<RuleItem>,
        series: List<RuleSeries> = emptyList(),
    ) = Titles.of(snapshot(items = items, series = series), items.map { it.item })

    @Test
    fun aSeriesTakesItsSeriesFields() {
        val s = idOf(1)
        val sr =
            series(s, rating = "TV-14", genres = listOf("Drama"), tags = listOf("t1"), studios = listOf("Studio A"))
        val e1 = episode(idOf(10), s, genres = listOf("Mystery", "Drama"), studios = listOf("Studio B"))
        val e2 = episode(idOf(11), s, genres = listOf("Mystery"), tags = listOf("t2"))
        val title = titlesOf(listOf(e1, e2), listOf(sr)).getValue(s)
        assertTrue(title.isSeries)
        assertEquals(listOf("Drama", "Mystery"), title.genres)
        assertEquals(listOf("t1", "t2"), title.tags)
        assertEquals(listOf("Studio A", "Studio B"), title.studios)
        assertEquals("TV-14", title.rating)
        assertEquals(1, titlesOf(listOf(e1, e2), listOf(sr)).size)
    }

    @Test
    fun animeByGenreTagOrAnimatedStudio() {
        val byGenre = movie(idOf(1), genres = listOf("Anime"))
        val byTag = movie(idOf(2), tags = listOf("Anime"))
        val byStudio = movie(idOf(3), genres = listOf("Animation"), studios = listOf(animeStudio))
        val plain = movie(idOf(4), genres = listOf("Animation"))
        val titles = titlesOf(listOf(byGenre, byTag, byStudio, plain))
        assertEquals(TitleFormat.Anime, titles.getValue(idOf(1)).format)
        assertEquals(TitleFormat.Anime, titles.getValue(idOf(2)).format)
        assertEquals(TitleFormat.Anime, titles.getValue(idOf(3)).format)
        assertEquals(TitleFormat.Animation, titles.getValue(idOf(4)).format)
    }

    @Test
    fun aLiveActionFilmFromAnAnimeStudioIsLive() {
        val film = movie(idOf(1), genres = listOf("Drama"), studios = listOf(animeStudio))
        assertEquals(TitleFormat.Live, titlesOf(listOf(film)).getValue(idOf(1)).format)
    }

    @Test
    fun adultAnimationByRatingOrNetwork() {
        val byRating = movie(idOf(1), genres = listOf("Animation"), rating = "TV-MA")
        val byNetwork = movie(idOf(2), genres = listOf("Animation"), studios = listOf(adultNetwork))
        val kid = movie(idOf(3), genres = listOf("Animation"), rating = "TV-PG")
        val unrated = movie(idOf(4), genres = listOf("Animation"))
        val live = movie(idOf(5), genres = listOf("Drama"), rating = "TV-MA")
        val anime = movie(idOf(6), genres = listOf("Anime"), rating = "TV-MA")
        val titles = titlesOf(listOf(byRating, byNetwork, kid, unrated, live, anime))
        assertTrue(titles.getValue(idOf(1)).adultAnimation)
        assertTrue(titles.getValue(idOf(2)).adultAnimation)
        assertFalse(titles.getValue(idOf(3)).adultAnimation)
        assertFalse(titles.getValue(idOf(4)).adultAnimation)
        assertFalse(titles.getValue(idOf(5)).adultAnimation)
        assertFalse(titles.getValue(idOf(6)).adultAnimation)
    }

    @Test
    fun madeForKidsComesFromTheKidHoldNotFromCandidacy() {
        val s = idOf(1)
        val kidSeries = series(s, genres = listOf("Kids"))
        val ep = episode(idOf(10), s)
        val kidFilm = movie(idOf(2), rating = "TV-Y")
        val grownUpFilm = movie(idOf(3), rating = "R", genres = listOf("Drama"))
        val animatedFamily = idOf(4)
        val familySeries = series(animatedFamily, rating = "G", genres = listOf("Animation", "Family"))
        val familyEp = episode(idOf(11), animatedFamily)
        // A G-rated family drama: a kid channel's candidacy might take it, but the hold does not call it made for kids.
        val familyDrama = movie(idOf(5), rating = "G", genres = listOf("Family", "Drama"))
        val titles =
            titlesOf(
                listOf(ep, kidFilm, grownUpFilm, familyEp, familyDrama),
                listOf(kidSeries, familySeries),
            )
        assertTrue(titles.getValue(s).madeForKids)
        assertTrue(titles.getValue(idOf(2)).madeForKids)
        assertFalse(titles.getValue(idOf(3)).madeForKids)
        assertTrue(titles.getValue(animatedFamily).madeForKids)
        assertFalse(titles.getValue(idOf(5)).madeForKids)
    }

    @Test
    fun anEpisodeWithoutItsSeriesIsGroupedAndJudgedOnItsOwn() {
        val s = idOf(1)
        val titles = titlesOf(listOf(episode(idOf(10), s, genres = listOf("Kids")), episode(idOf(11), s)))
        assertEquals(1, titles.size)
        assertTrue(titles.getValue(s).madeForKids)
    }

    @Test
    fun aSeriesLessGroupIsNotForKidsWhenNoEpisodePasses() {
        val s = idOf(1)
        val titles = titlesOf(listOf(episode(idOf(10), s, genres = listOf("Drama")), episode(idOf(11), s)))
        assertFalse(titles.getValue(s).madeForKids)
    }

    @Test
    fun aTitlesFactsComeFromTheWholeSnapshot() {
        val s = idOf(1)
        val sr = series(s)
        val e1 = episode(idOf(10), s, genres = listOf("Anime"))
        val e2 = episode(idOf(11), s, genres = listOf("Drama"))
        val snap = snapshot(items = listOf(e1, e2), series = listOf(sr))
        // Only e2 is passed, but e1 still makes the series anime.
        val title = Titles.of(snap, listOf(e2.item)).getValue(s)
        assertEquals(TitleFormat.Anime, title.format)
        assertEquals(listOf("Anime", "Drama"), title.genres)
    }

    @Test
    fun anUnratedSeriesTakesTheHighestEpisodeRatingWhateverTheOrder() {
        val s = idOf(1)
        val sr = series(s)
        val eps =
            listOf(
                episode(idOf(10), s, rating = "TV-PG"),
                episode(idOf(11), s, rating = "TV-MA"),
                episode(idOf(12), s, rating = "TV-Y"),
                episode(idOf(13), s),
            )
        for (order in listOf(eps, eps.reversed())) {
            val snap = snapshot(items = order, series = listOf(sr))
            assertEquals("TV-MA", Titles.of(snap, listOf(order[0].item)).getValue(s).rating)
        }
        val rated = snapshot(items = eps, series = listOf(series(s, rating = "TV-14")))
        assertEquals("TV-14", Titles.of(rated, listOf(eps[0].item)).getValue(s).rating)
    }
}
