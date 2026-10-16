// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.testing

import tv.stationbreak.core.model.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The generated library the sync tests run against (F0.6): deterministic, valid, and a little broken on purpose. */
class SyntheticLibraryTest {
    @Test
    fun theSameSizeAndSeedGiveIdenticalItems() {
        val first = SyntheticLibrary(size = 500, seed = 7).items.toList()
        val second = SyntheticLibrary(size = 500, seed = 7).items.toList()

        assertEquals(first, second)
        assertEquals(500, first.size)
    }

    @Test
    fun aDifferentSeedGivesDifferentItems() {
        val a = SyntheticLibrary(size = 200, seed = 1).items.toList()
        val b = SyntheticLibrary(size = 200, seed = 2).items.toList()

        assertNotEquals(a, b)
    }

    @Test
    fun anItemIsTheSameWhetherReadAloneOrInTheSequence() {
        val library = SyntheticLibrary(size = 300, seed = 3)

        assertEquals(library.items.toList()[123], library.item(123))
    }

    @Test
    fun aPageIsASliceOfTheWholeAndRunsOutAtTheEnd() {
        val library = SyntheticLibrary(size = 25, seed = 3)
        val all = library.items.toList()

        assertEquals(all.subList(10, 20), library.page(10, 10))
        assertEquals(all.subList(20, 25), library.page(20, 10))
        assertEquals(emptyList(), library.page(25, 10))
    }

    @Test
    fun idsAreUniqueSafeAndAlreadyInAscendingOrder() {
        val ids = SyntheticLibrary(size = 2_000, seed = 5).items.map { it.id.value }.toList()

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.all { id -> id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' } })
    }

    @Test
    fun everyItemHasACreationDateAndTheLibraryAsItsParent() {
        val library = SyntheticLibrary(size = 1_000, seed = 9)

        for (item in library.items) {
            assertNotNull(item.dateCreated, item.id.value)
            assertNotNull(item.dateLastSaved, item.id.value)
            assertEquals(library.libraryId, item.parentId)
        }
    }

    @Test
    fun onlyVideoKindsAreMadeAndEpisodesCarryTheirSeries() {
        val items = SyntheticLibrary(size = 1_000, seed = 11).items.toList()
        val video = setOf(ItemKind.Movie, ItemKind.Episode, ItemKind.Video, ItemKind.MusicVideo)

        assertTrue(items.all { it.kind in video })
        assertTrue(items.any { it.kind == ItemKind.Episode })
        for (episode in items.filter { it.kind == ItemKind.Episode }) {
            assertNotNull(episode.seriesId)
            assertNotNull(episode.seasonNumber)
            assertNotNull(episode.episodeNumber)
        }
    }

    @Test
    fun someItemsHaveNoRuntimeOrAZeroOneAndTheRestHaveAPositiveOne() {
        val items = SyntheticLibrary(size = 2_000, seed = 13).items.toList()

        assertTrue(items.any { it.runtime == null })
        assertTrue(items.any { it.runtime == Duration.ZERO })
        val usable = items.filter { it.runtime != null && it.runtime != Duration.ZERO }
        assertTrue(usable.size > items.size / 2)
        assertTrue(usable.all { checkNotNull(it.runtime) > Duration.ZERO })
    }

    @Test
    fun anEmptyLibraryIsFineAndANegativeSizeIsNot() {
        assertEquals(emptyList(), SyntheticLibrary(size = 0).items.toList())
        assertFailsWith<IllegalArgumentException> { SyntheticLibrary(size = -1) }
        assertFailsWith<IllegalArgumentException> { SyntheticLibrary(size = 3).item(3) }
    }
}
