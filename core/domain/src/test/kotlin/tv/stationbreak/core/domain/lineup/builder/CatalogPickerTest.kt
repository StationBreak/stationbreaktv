// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LIBRARY_ID
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** What the channel builder's pickers read (spec 22). */
class CatalogPickerTest {
    private val repository = InMemoryLibraryRepository()
    private val movies = Library(ItemId(LIBRARY_ID.value), "Test Movies", LibraryKind.Movies)
    private val other = Library(ItemId(id(0xB2).value), "Other Library", LibraryKind.TvShows)
    private var server: List<Library>? = listOf(other, movies)
    private var chosen: Set<ItemId>? = null
    private val picker = LibraryCatalogPicker(repository, { server }, { chosen })

    private fun film(
        n: Int,
        name: String,
        genres: List<String> = emptyList(),
    ) = MediaItem(ItemId(id(n).value), name, ItemKind.Movie, 90.minutes, movies.id, genres = genres)

    private fun entry(library: Library) = PickerEntry(library.id.value, library.name)

    @Test
    fun librariesUseTheServersNames() =
        runBlocking<Unit> {
            val listed = picker.libraries(ACCOUNT)

            val expected = listOf(entry(other), entry(movies))
            assertEquals(Listed.Ok(expected), listed)
        }

    @Test
    fun onlyTheChosenLibrariesAreListedWhenSomeAreChosen() =
        runBlocking<Unit> {
            chosen = setOf(movies.id)

            assertEquals(Listed.Ok(listOf(PickerEntry(movies.id.value, "Test Movies"))), picker.libraries(ACCOUNT))
        }

    @Test
    fun librariesAreUnavailableWhenTheServerIsUnreachable() =
        runBlocking<Unit> {
            server = null
            repository.seed(ACCOUNT, movies.id, film(1, "Film 1", listOf("Comedy")))
            repository.seedCollectionName(ACCOUNT, id(0xC1), "Box Set 1")

            assertEquals(Listed.Unavailable, picker.libraries(ACCOUNT))
            assertEquals(listOf(PickerEntry("Comedy", "Comedy")), picker.genres(ACCOUNT))
            assertEquals(1, picker.collections(ACCOUNT).size)
        }

    @Test
    fun emptyPickersAreEmptyLists() =
        runBlocking<Unit> {
            assertTrue(picker.collections(ACCOUNT).isEmpty())
            assertTrue(picker.tags(ACCOUNT).isEmpty())
            assertTrue(picker.genres(ACCOUNT).isEmpty())
            assertTrue(picker.search(ACCOUNT, "anything").isEmpty())
            server = emptyList()
            assertEquals(Listed.Ok(emptyList()), picker.libraries(ACCOUNT))
        }

    @Test
    fun genresAndTagsListItemsAndSeriesWithTheirOwnWordsAsKeyAndLabel() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "Film 1", listOf("Drama")))
            repository.seedSeries(ACCOUNT, RuleSeries(id(0x51), null, listOf("Comedy"), listOf("kept")))

            assertEquals(listOf("Comedy", "Drama"), picker.genres(ACCOUNT).map { it.key })
            assertEquals(listOf(PickerEntry("kept", "kept")), picker.tags(ACCOUNT))
        }

    @Test
    fun collectionsAreSortedByFoldedName() =
        runBlocking<Unit> {
            repository.seedCollectionName(ACCOUNT, id(0xC1), "beta Set")
            repository.seedCollectionName(ACCOUNT, id(0xC2), "Alpha Set")
            repository.seedCollectionName(ACCOUNT, id(0xC3), "Gamma Set")

            assertEquals(
                listOf("Alpha Set", "beta Set", "Gamma Set"),
                picker.collections(ACCOUNT).map { it.label },
            )
            assertEquals(id(0xC2).value, picker.collections(ACCOUNT).first().key)
        }

    @Test
    fun searchRefusesTooShortAndTooLongText() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "Film One"))

            assertTrue(picker.search(ACCOUNT, "F").isEmpty())
            assertTrue(picker.search(ACCOUNT, "  F  ").isEmpty())
            assertTrue(picker.search(ACCOUNT, "x".repeat(65)).isEmpty())
            assertEquals(listOf("Film One"), picker.search(ACCOUNT, "  fi ").map { it.name })
            assertEquals(TitleKind.Film, picker.search(ACCOUNT, "Film").single().kind)
            // 64 code points of two UTF-16 units each are within the bound; 65 are not.
            val astral = "😀"
            assertTrue(picker.search(ACCOUNT, astral.repeat(65)).isEmpty())
            assertTrue(picker.search(ACCOUNT, astral.repeat(64)).isEmpty(), "within the bound, but nothing matches")
        }

    @Test
    fun sourceLabelsUseTheNamesTheLibraryAndServerGive() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "Film One"))
            repository.seedSeriesName(ACCOUNT, id(0x51), "Show One")
            repository.seedCollectionName(ACCOUNT, id(0xC1), "Box Set 1")
            val labels = SourceLabels(repository) { server }
            val sources =
                listOf(
                    DraftSource.Library(movies.id.value, ""),
                    DraftSource.Collection(id(0xC1).value, ""),
                    DraftSource.Show(id(0x51).value, ""),
                    DraftSource.Pick(id(1).value, ""),
                    DraftSource.Genre("Comedy", ""),
                    DraftSource.Pick(id(0x99).value, ""),
                )

            val words = labels(ACCOUNT, sources)

            assertEquals(
                listOf("Test Movies", "Box Set 1", "Show One", "Film One", "Comedy", id(0x99).value),
                sources.map { words.getValue(it) },
            )
        }

    @Test
    fun sourceLabelsFallBackToTheKeyWhenTheServerCannotBeReached() =
        runBlocking<Unit> {
            server = null
            val source = DraftSource.Library(movies.id.value, "")

            assertEquals(movies.id.value, SourceLabels(repository) { server }(ACCOUNT, listOf(source)).getValue(source))
        }
}
