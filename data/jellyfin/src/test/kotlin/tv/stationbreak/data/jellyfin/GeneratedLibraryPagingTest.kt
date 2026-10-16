// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** The adapter reading a 15,000-item generated library page by page: nothing missing, nothing repeated (S0.6.1). */
class GeneratedLibraryPagingTest {
    private val jellyfin = FakeJellyfin()
    private val synthetic = SyntheticLibrary(size = 15_000, seed = 42)
    private val library = Library(synthetic.libraryId, "Generated", LibraryKind.Mixed)

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun readsEveryItemOnceAndInOrderInPagesOfTheLimit() {
        runBlocking {
            jellyfin.serve(synthetic)
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)
            val pageSize = MediaServer.MAX_PAGE_SIZE

            val seen = mutableListOf<ItemId>()
            var total: Int? = null
            var pages = 0
            var start = 0
            while (true) {
                val page = assertNotNull(adapter.libraryItems(session, library, start, pageSize).getOrNull())
                if (start == 0) total = page.totalCount else assertNull(page.totalCount, "page at $start")
                seen += page.items.map { it.id }
                pages++
                if (page.items.size < pageSize) break
                start += page.items.size
            }

            assertEquals(15_000, total)
            assertEquals(75 + 1, pages) // 75 full pages, then the empty one that ends the walk
            assertEquals(synthetic.items.map { it.id }.toList(), seen)
            assertEquals(seen.size, seen.toSet().size)
        }
    }

    @Test
    fun theItemsComeBackWithTheirCacheFieldsIntact() {
        runBlocking {
            jellyfin.serve(synthetic)

            val page =
                testAdapter().libraryItems(testSession(jellyfin.address), library, 400, 200).getOrNull()

            assertEquals(synthetic.page(400, 200), page?.items)
        }
    }

    @Test
    fun theIdListingReadsEveryIdOnceToo() {
        runBlocking {
            jellyfin.serve(synthetic)
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            val seen = mutableListOf<ItemId>()
            var start = 0
            while (true) {
                val ids = adapter.libraryItemIds(session, library, start, MediaServer.MAX_PAGE_SIZE)
                val page = assertNotNull(ids.getOrNull())
                seen += page.ids
                if (page.ids.size < MediaServer.MAX_PAGE_SIZE) break
                start += page.ids.size
            }

            assertEquals(synthetic.items.map { it.id }.toList(), seen)
        }
    }

    @Test
    fun changedSinceReturnsOnlyItemsSavedAtOrAfterTheMoment() {
        runBlocking {
            jellyfin.serve(synthetic)
            val moment = Instant.parse("2024-01-01T00:00:00Z")
            val expected =
                synthetic.items
                    .filter { checkNotNull(it.dateLastSaved) >= moment }
                    .map { it.id }
                    .toList()

            val adapter = testAdapter()
            val session = testSession(jellyfin.address)
            val seen = mutableListOf<ItemId>()
            var start = 0
            while (true) {
                val result = adapter.libraryItems(session, library, start, MediaServer.MAX_PAGE_SIZE, moment)
                val page = assertNotNull(result.getOrNull())
                seen += page.items.map { it.id }
                if (page.items.size < MediaServer.MAX_PAGE_SIZE) break
                start += page.items.size
            }

            assertEquals(expected, seen)
        }
    }
}
