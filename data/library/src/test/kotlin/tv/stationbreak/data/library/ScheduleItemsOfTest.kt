// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.ScheduleItemsOfContract
import tv.stationbreak.core.model.CanonicalId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The read by id of a repaired day's tail pool (spec 26): the shared contract, the as-of rule and the chunks. */
class ScheduleItemsOfTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private fun canon(n: Int) = CanonicalId.parse(hexId(n))

    @Test
    fun keepsTheContract() =
        runBlocking {
            ScheduleItemsOfContract.check(
                ScheduleItemsOfContract.Fixture(
                    repository = repository,
                    account = ALICE,
                    save = { library, items -> repository.put(ALICE, library, *items.toTypedArray()) },
                    delete = { ids -> repository.tombstone(ALICE, ids) },
                    cutoffMs = clock.nowMs + 60_000,
                    createdAt = EPOCH,
                ),
            )
        }

    @Test
    fun anEditAfterTheCutoff_givesTheVersionHeldBeforeIt_asScheduleItemsDoes() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes), sitem(2))
            val before = clock.nowMs + 60_000
            clock.stepMs(10 * 60_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 60.minutes))
            val after = clock.nowMs + 60_000
            val ids = setOf(canon(1), canon(2))

            for ((cutoff, minutes) in listOf(before to 90, after to 60)) {
                val got = repository.scheduleItemsOf(ALICE, ids, cutoff)
                assertEquals(minutes.minutes.inWholeMilliseconds, got.first { it.id == canon(1) }.runtimeMs)
                val all = repository.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoff))
                assertEquals(all.filter { it.id in ids }, got)
            }
        }

    @Test
    fun anItemFirstSeenAfterTheCutoff_isItsOldestVersion_andADeletionAfterItKeepsIt() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val cutoff = clock.nowMs - 60_000
            repository.put(ALICE, MOVIES, sitem(3, runtime = 30.minutes))
            clock.stepMs(60_000)
            repository.put(ALICE, MOVIES, sitem(3, runtime = 40.minutes))
            clock.stepMs(60_000)
            repository.tombstone(ALICE, listOf(sitem(3).id))

            val got = repository.scheduleItemsOf(ALICE, setOf(canon(3)), cutoff)

            assertEquals(listOf(30.minutes.inWholeMilliseconds), got.map { it.runtimeMs })
            val all = repository.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoff))
            assertEquals(all, got)
        }

    @Test
    fun moreIdsThanSqliteBinds_areReadInChunks() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val count = LibraryDao.MAX_VARIABLES * 2 + 7
            repository.put(ALICE, MOVIES, *(1..count).map { sitem(it) }.toTypedArray())
            val ids = (1..count + 50).mapTo(HashSet()) { canon(it) }

            val got = repository.scheduleItemsOf(ALICE, ids, clock.nowMs + 60_000)

            assertEquals((1..count).map { canon(it) }.sortedBy { it.value }, got.map { it.id })
        }

    @Test
    fun anotherAccountsItemsAreNotRead() =
        runBlocking {
            repository.select(BOB, setOf(MOVIES))
            repository.put(BOB, MOVIES, sitem(5))

            assertEquals(emptyList<Any>(), repository.scheduleItemsOf(ALICE, setOf(canon(5)), clock.nowMs + 60_000))
        }
}
