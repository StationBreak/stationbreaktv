// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 10 task 1.1 (#266): the item history moved out of `LibraryDao` into `ItemHistoryDao`, and no behaviour changed.
 * The spec 09 suites still run unchanged through the repository; this one asks the split DAOs directly the same
 * questions: where the versions live, the change test, the monotonic stamp, the revival copy and the as-of read.
 */
class ItemHistoryDaoSplitTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val history = repository.history
    private val library = repository.dao
    private val server = ALICE.server.value
    private val user = ALICE.user.value
    private val t0 = clock.nowMs

    @After
    fun close() = repository.close()

    private suspend fun stored(id: String): ItemEntity = library.itemsByIds(server, user, listOf(id)).single()

    private fun rows(
        item: ItemEntity,
        genres: List<String> = emptyList(),
    ) = ItemRows(item, genres.mapIndexed { i, name -> GenreEntity(server, user, item.itemId, i, name) }, emptyList())

    @Test
    fun theVersionsAreKeptAndReadByTheHistoryDao() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes))

            val version = history.allVersions(server, user).single()

            assertEquals(1, history.countVersions())
            assertEquals(t0, version.seenFromMs)
            assertEquals(t0 + 10_000, version.supersededAtMs)
            assertEquals(90.minutes.inWholeMilliseconds, version.runtimeMs)
            assertEquals(t0 + 10_000, stored(hexId(1)).seenFromMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun theAsOfReadThroughTheHistoryDaoPicksTheVersionHeldBeforeTheCutoff() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes))
            val libraries = setOf(MOVIES.value)

            fun runtimeAt(cutoffMs: Long) = runBlocking { history.heldAsOf(server, user, cutoffMs, 100, libraries) }.single().runtimeMs

            // Between the two stamps the old version was current; after both, the current row; before both, the oldest.
            assertEquals(90.minutes.inWholeMilliseconds, runtimeAt(t0 + 5_000))
            assertEquals(100.minutes.inWholeMilliseconds, runtimeAt(t0 + 20_000))
            assertEquals(90.minutes.inWholeMilliseconds, runtimeAt(t0 - 60_000))
        }

    @Test
    fun theChangeTestVersionsAScheduleFieldAndNothingElse() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))
            val old = stored(hexId(1))

            val same = history.planPage(mapOf(old.itemId to rows(old)), mapOf(old.itemId to old), emptyMap(), emptyMap(), t0 + 1)
            val renamed = old.copy(name = "A new name")
            val nameOnly =
                history.planPage(mapOf(old.itemId to rows(renamed)), mapOf(old.itemId to old), emptyMap(), emptyMap(), t0 + 1)
            val longer = old.copy(runtimeMs = 1)
            val edited =
                history.planPage(mapOf(old.itemId to rows(longer)), mapOf(old.itemId to old), emptyMap(), emptyMap(), t0 + 1)
            val withGenre =
                history.planPage(
                    mapOf(old.itemId to rows(old, listOf("Drama"))),
                    mapOf(old.itemId to old),
                    emptyMap(),
                    emptyMap(),
                    t0 + 1,
                )

            assertEquals(1, same.unchanged)
            assertEquals(emptyList<ItemVersionEntity>(), nameOnly.versions)
            assertEquals(listOf(renamed.copy(seenFromMs = old.seenFromMs)), nameOnly.toUpdate)
            assertEquals(1, edited.versions.size)
            assertEquals(1, withGenre.versions.size)
        }

    @Test
    fun theStampIsAlwaysAfterTheOneItReplaces() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))
            val old = stored(hexId(1))
            val edit = old.copy(runtimeMs = 1)

            // The clock reads the same millisecond, and then an earlier one.
            val sameMs = history.planPage(mapOf(old.itemId to rows(edit)), mapOf(old.itemId to old), emptyMap(), emptyMap(), t0)
            val backwards =
                history.planPage(mapOf(old.itemId to rows(edit)), mapOf(old.itemId to old), emptyMap(), emptyMap(), t0 - 5_000)

            assertEquals(t0 + 1, sameMs.toUpdate.single().seenFromMs)
            assertEquals(t0 + 1, sameMs.versions.single().supersededAtMs)
            assertEquals(t0 + 1, backwards.toUpdate.single().seenFromMs)
        }

    @Test
    fun aNewItemIsStampedNowAndHasNoVersion() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))
            val fresh = stored(hexId(1)).copy(itemId = hexId(2), seenFromMs = 0, firstSeenMs = 0)

            val plan = history.planPage(mapOf(fresh.itemId to rows(fresh)), emptyMap(), emptyMap(), emptyMap(), t0 + 7)

            assertEquals(listOf(fresh.copy(seenFromMs = t0 + 7, firstSeenMs = t0 + 7)), plan.toInsert)
            assertEquals(emptyList<ItemVersionEntity>(), plan.versions)
        }

    @Test
    fun aRevivalIsCopiedAsAVersionEndingAtTheNewStamp() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(10_000)
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            val tombstoned = stored(hexId(1))

            val plan = history.planRevival(listOf(tombstoned), mapOf(tombstoned.itemId to listOf("Drama")), emptyMap(), t0 + 20_000)

            val version = plan.versions.single()
            assertEquals(t0, version.seenFromMs)
            assertEquals(t0 + 20_000, version.supersededAtMs)
            assertEquals(t0 + 10_000, version.deletedAtMs)
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertNull(plan.revived.single().deletedAtMs)
            assertEquals(t0 + 20_000, plan.revived.single().seenFromMs)
        }
}
