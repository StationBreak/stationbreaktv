// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LIBRARY_ID
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun <T> Listed<T>.value(): T = (this as Listed.Ok<T>).value

    @Test
    fun librariesAreUnavailableWhenTheServerIsUnreachable() =
        runBlocking<Unit> {
            server = null
            repository.seed(ACCOUNT, movies.id, film(1, "Film 1", listOf("Comedy")))
            repository.seedCollectionName(ACCOUNT, id(0xC1), "Box Set 1")

            assertEquals(Listed.Unavailable, picker.libraries(ACCOUNT))
            assertEquals(Listed.Ok(listOf(PickerEntry("Comedy", "Comedy"))), picker.genres(ACCOUNT))
            assertEquals(1, picker.collections(ACCOUNT).value().size)
        }

    @Test
    fun emptyPickersAreEmptyLists() =
        runBlocking<Unit> {
            assertEquals(Listed.Ok(emptyList()), picker.collections(ACCOUNT))
            assertEquals(Listed.Ok(emptyList()), picker.tags(ACCOUNT))
            assertEquals(Listed.Ok(emptyList()), picker.genres(ACCOUNT))
            assertEquals(Listed.Ok(emptyList()), picker.search(ACCOUNT, "anything"))
            server = emptyList()
            assertEquals(Listed.Ok(emptyList()), picker.libraries(ACCOUNT))
        }

    @Test
    fun everyReadThatThrowsIsUnavailableAndCancellationGoesThrough() =
        runBlocking<Unit> {
            val broken =
                object : LibraryRepository by repository {
                    override suspend fun collectionNames(account: LibraryAccount): CollectionNames = error("gone")

                    override suspend fun distinctTags(account: LibraryAccount): List<String> = error("gone")

                    override suspend fun distinctGenres(account: LibraryAccount): List<String> = error("gone")

                    override suspend fun searchTitles(
                        account: LibraryAccount,
                        text: String,
                        limit: Int,
                    ): List<TitleHit> = error("gone")
                }
            val failing = LibraryCatalogPicker(broken, { error("gone") })

            assertEquals(Listed.Unavailable, failing.libraries(ACCOUNT))
            assertEquals(Listed.Unavailable, failing.collections(ACCOUNT))
            assertEquals(Listed.Unavailable, failing.tags(ACCOUNT))
            assertEquals(Listed.Unavailable, failing.genres(ACCOUNT))
            assertEquals(Listed.Unavailable, failing.search(ACCOUNT, "anything"))
            val cancelling = LibraryCatalogPicker(repository, { throw CancellationException("stop") })
            assertFailsWith<CancellationException> { cancelling.libraries(ACCOUNT) }
        }

    @Test
    fun genresAndTagsListItemsAndSeriesWithTheirOwnWordsAsKeyAndLabel() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "Film 1", listOf("Drama")))
            repository.seedSeries(ACCOUNT, RuleSeries(id(0x51), null, listOf("Comedy"), listOf("kept")))

            assertEquals(listOf("Comedy", "Drama"), picker.genres(ACCOUNT).value().map { it.key })
            assertEquals(Listed.Ok(listOf(PickerEntry("kept", "kept"))), picker.tags(ACCOUNT))
        }

    @Test
    fun collectionsAreSortedByFoldedName() =
        runBlocking<Unit> {
            repository.seedCollectionName(ACCOUNT, id(0xC1), "beta Set")
            repository.seedCollectionName(ACCOUNT, id(0xC2), "Alpha Set")
            repository.seedCollectionName(ACCOUNT, id(0xC3), "Gamma Set")

            val listed = picker.collections(ACCOUNT).value()

            assertEquals(listOf("Alpha Set", "beta Set", "Gamma Set"), listed.map { it.label })
            assertEquals(id(0xC2).value, listed.first().key)
        }

    @Test
    fun searchRefusesTooShortAndTooLongText() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "Film One"))
            val none = Listed.Ok(emptyList<TitleHit>())

            assertEquals(none, picker.search(ACCOUNT, "F"))
            assertEquals(none, picker.search(ACCOUNT, "  F  "))
            assertEquals(none, picker.search(ACCOUNT, "x".repeat(65)))
            assertEquals(listOf("Film One"), picker.search(ACCOUNT, "  fi ").value().map { it.name })
            assertEquals(
                TitleKind.Film,
                picker
                    .search(ACCOUNT, "Film")
                    .value()
                    .single()
                    .kind,
            )
            // 64 code points of two UTF-16 units each are within the bound; 65 are not.
            val astral = "\uD83D\uDE00"
            assertEquals(none, picker.search(ACCOUNT, astral.repeat(65)))
            assertEquals(none, picker.search(ACCOUNT, astral.repeat(64)))
        }

    @Test
    fun searchResultsAreSortedByFoldedName() =
        runBlocking<Unit> {
            repository.seed(ACCOUNT, movies.id, film(1, "beta Film"), film(2, "Alpha Film"), film(3, "Gamma Film"))

            val names = picker.search(ACCOUNT, "Film").value().map { it.name }

            assertEquals(listOf("Alpha Film", "beta Film", "Gamma Film"), names)
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
    fun aStudioStoredFoldedIsShownWithTheSpellingTheLibraryHolds() =
        runBlocking<Unit> {
            val first = film(1, "Film One").copy(studios = listOf("STUDIO A"))
            val second = film(2, "Film Two").copy(studios = listOf("Studio A", "Studio B"))
            repository.seed(ACCOUNT, movies.id, first, second)
            val labels = SourceLabels(repository) { server }

            val shown = labels.studios(ACCOUNT, listOf("studio a", "studio b", "studio unknown"))

            val expected =
                mapOf("studio a" to "STUDIO A", "studio b" to "Studio B", "studio unknown" to "studio unknown")
            assertEquals(expected, shown)
        }

    @Test
    fun sourceLabelsFallBackToTheKeyWhenTheServerCannotBeReached() =
        runBlocking<Unit> {
            server = null
            val source = DraftSource.Library(movies.id.value, "")

            assertEquals(movies.id.value, SourceLabels(repository) { server }(ACCOUNT, listOf(source)).getValue(source))
        }

    @Test
    fun aShowOrFilmTheLibraryDoesNotKnowFallsBackToItsKey() =
        runBlocking<Unit> {
            val show = DraftSource.Show(id(0x77).value, "")
            val notAnId = DraftSource.Show("not-an-id", "")

            val words = SourceLabels(repository) { server }(ACCOUNT, listOf(show, notAnId))

            assertEquals(id(0x77).value, words.getValue(show))
            assertEquals("not-an-id", words.getValue(notAnId))
        }

    @Test
    fun theSearchLengthIsTwoToSixtyFourCodePoints() {
        assertEquals(2..64, CatalogPicker.SEARCH_LENGTH)
    }
}
