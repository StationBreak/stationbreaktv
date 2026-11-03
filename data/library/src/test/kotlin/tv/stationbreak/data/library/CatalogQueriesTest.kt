// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import kotlin.time.Duration.Companion.minutes

/** What the channel builder's pickers read (spec 22): genres, tags and titles of the saved library. */
class CatalogQueriesTest {
    private val repository = TestLibraries.inMemory(FakeClock())

    @After
    fun close() = repository.close()

    private var run = 0L

    private fun movie(
        n: Int,
        name: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        year: Int? = null,
    ) = MediaItem(
        ItemId(hexId(0x1000 + n)),
        name,
        ItemKind.Movie,
        90.minutes,
        MOVIES,
        dateCreated = EPOCH - 1.minutes,
        productionYear = year,
        genres = genres,
        tags = tags,
    )

    private fun show(
        n: Int,
        name: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = SeriesInfo(ItemId(hexId(0x5000 + n)), name, null, genres, tags)

    private fun episode(
        n: Int,
        show: SeriesInfo,
    ) = MediaItem(
        ItemId(hexId(0x2000 + n)),
        "Episode $n",
        ItemKind.Episode,
        30.minutes,
        MOVIES,
        dateCreated = EPOCH - 1.minutes,
        seriesId = show.id,
        seasonNumber = 1,
        episodeNumber = n,
        genres = listOf("Episode Only Genre"),
    )

    // Everything in one library of ALICE's, as one page.
    private suspend fun save(
        movies: List<MediaItem> = emptyList(),
        shows: List<SeriesInfo> = emptyList(),
        episodes: List<MediaItem> = shows.mapIndexed { i, s -> episode(i, s) },
    ) {
        repository.select(ALICE, setOf(MOVIES))
        val items = movies + episodes
        repository.writePage(ALICE, MOVIES, items, items.size, items.size, run = ++run, series = shows)
    }

    private fun names(hits: List<TitleHit>) = hits.map { it.name }

    @Test
    fun distinctGenresCoverItemsAndSeriesOnceEach() =
        runBlocking<Unit> {
            save(
                movies =
                    listOf(
                        movie(1, "Film A", listOf("Comedy", "Drama")),
                        movie(2, "Film B", listOf("Comedy", "comedy")),
                    ),
                shows = listOf(show(1, "Show A", listOf("Drama", "Sci-Fi"))),
            )

            // Exact spelling, by folded name and then name; "Episode Only Genre" is the episode's own, still an item's.
            assertEquals(
                listOf("Comedy", "comedy", "Drama", "Episode Only Genre", "Sci-Fi"),
                repository.distinctGenres(ALICE),
            )
        }

    @Test
    fun distinctTagsCoverItemsAndSeriesAndOtherAccountsSeeNone() =
        runBlocking<Unit> {
            save(
                movies = listOf(movie(1, "Film A", tags = listOf("b-tag", "A-tag"))),
                shows = listOf(show(1, "Show A", tags = listOf("b-tag", "c-tag"))),
            )

            assertEquals(listOf("A-tag", "b-tag", "c-tag"), repository.distinctTags(ALICE))
            assertTrue(repository.distinctTags(BOB).isEmpty())
        }

    @Test
    fun deletedItemsAreNotListed() =
        runBlocking<Unit> {
            save(movies = listOf(movie(1, "Film A", listOf("Gone")), movie(2, "Film B", listOf("Kept"))))
            repository.tombstone(ALICE, listOf(ItemId(hexId(0x1001))))

            assertEquals(listOf("Kept"), repository.distinctGenres(ALICE))
            assertEquals(listOf("Film B"), names(repository.searchTitles(ALICE, "Film", 10)))
        }

    @Test
    fun distinctSeriesGenresAreDecodedFromThePackedColumn() =
        runBlocking<Unit> {
            // A colon and digits inside a name must not confuse the packed form.
            save(shows = listOf(show(1, "Show A", genres = listOf("A:B", "3:x", ""), tags = listOf("t"))))

            assertTrue(repository.distinctGenres(ALICE).containsAll(listOf("A:B", "3:x")))
            assertTrue("no empty name", "" !in repository.distinctGenres(ALICE))
        }

    @Test
    fun noRowCapOnTagsOrGenres() =
        runBlocking<Unit> {
            val movies = (0 until 600).map { movie(it, "Film $it", listOf("G$it"), listOf("T$it")) }
            save(movies = movies)

            assertEquals(600, repository.distinctTags(ALICE).size)
            assertEquals(600, repository.distinctGenres(ALICE).size)
        }

    @Test
    fun searchFindsShowsAndFilmsByPartOfAName() =
        runBlocking<Unit> {
            save(movies = listOf(movie(1, "The Test Film", year = 1999)), shows = listOf(show(1, "Test Show")))

            val hits = repository.searchTitles(ALICE, "est", 10)

            assertEquals(listOf("Test Show", "The Test Film"), names(hits))
            assertEquals(listOf(TitleKind.Show, TitleKind.Film), hits.map { it.kind })
            assertEquals(listOf(null, 1999), hits.map { it.year })
            assertTrue(repository.searchTitles(BOB, "est", 10).isEmpty())
        }

    @Test
    fun episodesAreNeverHits() =
        runBlocking<Unit> {
            save(shows = listOf(show(1, "Some Show")))

            assertEquals(listOf("Some Show"), names(repository.searchTitles(ALICE, "Some", 10)))
            assertTrue(repository.searchTitles(ALICE, "Episode", 10).isEmpty())
        }

    @Test
    fun aSearchWithWildcardsMatchesLiterally() =
        runBlocking<Unit> {
            save(movies = listOf(movie(1, "50% Off"), movie(2, "Under_Score"), movie(3, "Plain Name")))

            assertEquals(listOf("50% Off"), names(repository.searchTitles(ALICE, "%", 10)))
            assertEquals(listOf("Under_Score"), names(repository.searchTitles(ALICE, "_", 10)))
            assertTrue("a backslash matches nothing here", repository.searchTitles(ALICE, "\\", 10).isEmpty())
            assertEquals(listOf("Under_Score"), names(repository.searchTitles(ALICE, "r_S", 10)))
            assertTrue("_ is not a one-letter wildcard", repository.searchTitles(ALICE, "P_ain", 10).isEmpty())
        }

    @Test
    fun aSearchWithAQuoteIsHarmless() =
        runBlocking<Unit> {
            save(movies = listOf(movie(1, "It's Fine"), movie(2, "Other")))

            assertEquals(listOf("It's Fine"), names(repository.searchTitles(ALICE, "'s", 10)))
            assertTrue(repository.searchTitles(ALICE, "'; DROP TABLE item; --", 10).isEmpty())
            assertTrue(repository.searchTitles(ALICE, "' OR '1'='1", 10).isEmpty())
            assertEquals("the table is still there", 2, repository.searchTitles(ALICE, "e", 10).size)
        }

    @Test
    fun likeIsAsciiCaseInsensitiveLikeFoldNameAscii() =
        runBlocking<Unit> {
            save(movies = listOf(movie(1, "FILM NIGHT"), movie(2, "Étoile")))

            assertEquals(listOf("FILM NIGHT"), names(repository.searchTitles(ALICE, "film", 10)))
            assertEquals(listOf("FILM NIGHT"), names(repository.searchTitles(ALICE, "Night", 10)))
            assertEquals(listOf("Étoile"), names(repository.searchTitles(ALICE, "Ét", 10)))
            assertTrue("a non-ASCII letter is not folded", repository.searchTitles(ALICE, "ét", 10).isEmpty())
        }

    @Test
    fun searchIsCaseInsensitiveAndLimited() =
        runBlocking<Unit> {
            save(movies = (0 until 40).map { movie(it, "Alpha " + it.toString().padStart(2, '0')) })

            val hits = repository.searchTitles(ALICE, "ALPHA", 30)

            assertEquals(30, hits.size)
            assertEquals((0 until 30).map { "Alpha " + it.toString().padStart(2, '0') }, names(hits))
            assertTrue(repository.searchTitles(ALICE, "alpha", 0).isEmpty())
            assertTrue(repository.searchTitles(ALICE, "", 10).isEmpty())
        }

    @Test
    fun titleNamesCoverLiveShowsAndFilms() =
        runBlocking<Unit> {
            val s = show(1, "Show A")
            save(movies = listOf(movie(1, "Film A"), movie(2, "Film B")), shows = listOf(s))
            repository.tombstone(ALICE, listOf(ItemId(hexId(0x1002))))

            val names =
                repository.titleNames(
                    ALICE,
                    setOf(canon(0x1001), canon(0x1002), canon(0x5001), canon(0x9999)),
                )

            assertEquals(mapOf(canon(0x1001) to "Film A", canon(0x5001) to "Show A"), names)
        }
}
