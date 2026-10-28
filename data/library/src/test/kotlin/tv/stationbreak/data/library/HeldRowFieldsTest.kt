// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 1.5.3: the as-of read carries the rating, the ordered genres and the ordered tags of the version it
 * picks. A current row's come from the side tables, `ORDER BY itemId, position` by ID range; an older version's from
 * its codec columns. One `String` is shared per distinct value within a read (Vimes L5), and a rating of null, `""` and
 * `" "` stays three values.
 */
class HeldRowFieldsTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs

    @After
    fun close() = repository.close()

    private suspend fun held(cutoffMs: Long) =
        repository.history
            .heldAsOf(ALICE.server.value, ALICE.user.value, cutoffMs, 100, setOf(MOVIES.value))
            .associateBy { it.itemId }

    private fun movie(
        n: Int,
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = sitem(n).copy(officialRating = rating, genres = genres, tags = tags)

    @Test
    fun aCurrentRowCarriesItsRatingGenresAndTagsInTheServersOrder() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(
                ALICE,
                MOVIES,
                sitem(1).copy(officialRating = "TV-14", genres = listOf("Zed", "Alpha"), tags = listOf("t2", "t1")),
            )

            val row = held(start + 5_000).getValue(hexId(1))

            assertEquals("TV-14", row.officialRating)
            assertEquals(listOf("Zed", "Alpha"), row.genres)
            assertEquals(listOf("t2", "t1"), row.tags)
        }

    @Test
    fun anOlderVersionCarriesTheGenresAndTagsItHad() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, movie(1, "G", listOf("Old"), listOf("o")))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, movie(1, "R", listOf("New", "Newer")))

            val before = held(start + 5_000).getValue(hexId(1))
            val after = held(start + 20_000).getValue(hexId(1))

            assertEquals("G", before.officialRating)
            assertEquals(listOf("Old"), before.genres)
            assertEquals(listOf("o"), before.tags)
            assertEquals("R", after.officialRating)
            assertEquals(listOf("New", "Newer"), after.genres)
            assertEquals(emptyList<String>(), after.tags)
        }

    @Test
    fun nullEmptyAndBlankRatingsStayDistinctInTheAsOfRead() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(
                ALICE,
                MOVIES,
                sitem(1).copy(officialRating = null),
                sitem(2).copy(officialRating = ""),
                sitem(3).copy(officialRating = " "),
            )

            val rows = held(start + 5_000)

            assertEquals(null, rows.getValue(hexId(1)).officialRating)
            assertEquals("", rows.getValue(hexId(2)).officialRating)
            assertEquals(" ", rows.getValue(hexId(3)).officialRating)
        }

    @Test
    fun oneStringIsSharedPerDistinctValueWithinARead() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(
                ALICE,
                MOVIES,
                sitem(1).copy(genres = listOf("Drama"), tags = listOf("same")),
                sitem(2).copy(genres = listOf("Drama"), tags = listOf("same")),
            )
            clock.stepMs(10_000)
            // Edit both, so a read before the edit takes both from versions (the codec path).
            repository.put(
                ALICE,
                MOVIES,
                sitem(1).copy(genres = listOf("Drama", "x"), tags = listOf("same")),
                sitem(2).copy(genres = listOf("Drama", "x"), tags = listOf("same")),
            )

            val current = held(start + 20_000)
            val older = held(start + 5_000)

            assertSame(current.getValue(hexId(1)).genres[0], current.getValue(hexId(2)).genres[0])
            assertSame(current.getValue(hexId(1)).tags[0], current.getValue(hexId(2)).tags[0])
            assertSame(older.getValue(hexId(1)).genres[0], older.getValue(hexId(2)).genres[0])
            assertSame(older.getValue(hexId(1)).tags[0], older.getValue(hexId(2)).tags[0])
        }

    // Spec 18 section 2: studios ride the as-of read like genres, from the side table or the version's codec column.

    @Test
    fun aCurrentRowCarriesItsStudiosInTheServersOrderIntoTheRuleItem() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1).copy(studios = listOf("Zed Pictures", "Alpha Films")))

            val row = held(start + 5_000).getValue(hexId(1))
            val rule = repository.ruleSnapshot(ALICE, setOf(MOVIES), at(start + 5_000)).items.single()

            assertEquals(listOf("Zed Pictures", "Alpha Films"), row.studios)
            assertEquals(listOf("Zed Pictures", "Alpha Films"), rule.studios)
        }

    @Test
    fun aCutoffBeforeTheStudiosVersionReadsNoStudios() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(10_000)
            // The re-read after the upgrade: only studios differ.
            repository.put(ALICE, MOVIES, sitem(1).copy(studios = listOf("Invented Studio")))

            val before = repository.ruleSnapshot(ALICE, setOf(MOVIES), at(start + 5_000)).items.single()
            val after = repository.ruleSnapshot(ALICE, setOf(MOVIES), at(start + 20_000)).items.single()

            assertEquals(emptyList<String>(), before.studios)
            assertEquals(listOf("Invented Studio"), after.studios)
        }

    @Test
    fun anOlderVersionCarriesTheStudiosItHad() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1).copy(officialRating = "G", studios = listOf("Old Studio")))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1).copy(officialRating = "R", studios = listOf("New Studio")))

            assertEquals(listOf("Old Studio"), held(start + 5_000).getValue(hexId(1)).studios)
            assertEquals(listOf("New Studio"), held(start + 20_000).getValue(hexId(1)).studios)
        }

    @Test
    fun aRevivedItemKeepsItsStudiosInTheVersionAndTheRow() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1).copy(studios = listOf("Invented Studio")))
            clock.stepMs(10_000)
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            clock.stepMs(10_000)

            repository.revive(ALICE, listOf(ItemId(hexId(1))))

            val version = repository.versions(ALICE).single()
            assertEquals(listOf("Invented Studio"), VersionCodec.decode(version.studios))
            assertEquals(listOf("Invented Studio"), held(clock.nowMs + 1).getValue(hexId(1)).studios)
        }
}
