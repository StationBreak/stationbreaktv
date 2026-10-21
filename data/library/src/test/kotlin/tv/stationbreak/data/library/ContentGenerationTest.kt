// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #309: what the lineup refresher learns about the library. A launch sync that finds nothing (the reconcile and
 * the collection pass of every app open) must not look like a change, even though it records a new collection pass and
 * may move the last-success time; a sync that writes anything must. `contentGeneration` is that signal: durable, per account, and bumped inside the transaction of the write.
 */
class ContentGenerationTest {
    private val rig = StayCurrentRig()
    private val repository get() = rig.repository
    private val both = SyncPlan(incremental = true, reconcile = true)
    private val openOnly = SyncPlan(incremental = false, reconcile = true)

    @After
    fun close() = rig.close()

    private suspend fun changes() = repository.contentGeneration(ALICE)

    private suspend fun pass() = repository.collectionPassCompleted(ALICE).first()

    // The state a warm relaunch starts from: the first sync done, then a quiet hour.
    private suspend fun settled() {
        rig.firstSyncDone()
        rig.clock.instant += 1.hours
        rig.runner.run(openOnly)
    }

    @Test
    fun aLaunchSyncThatFindsNothingRecordsANewPassButChangesNothing() =
        runBlocking {
            settled()
            val before = changes()
            val passBefore = pass()
            assertNotNull(passBefore)

            rig.clock.instant += 1.hours
            rig.runner.run(openOnly)

            assertNotEquals("the pass records its own start every time", passBefore, pass())
            assertEquals("and wrote nothing the lineup could care about", before, changes())
        }

    @Test
    fun anIncrementalSyncThatFindsNothingNewChangesNothingEvenThoughItMovesLastSuccess() =
        runBlocking {
            settled()
            val before = changes()
            val successBefore = repository.activeStatus().first()?.lastSuccess

            rig.clock.instant += 7.hours
            rig.runner.run(both)

            assertNotEquals(successBefore, repository.activeStatus().first()?.lastSuccess)
            assertEquals(before, changes())
        }

    @Test
    fun anEditedItemIsAChange() =
        runBlocking {
            settled()
            val before = changes()
            val renamed = rig.library.items[3].copy(name = "Renamed")
            rig.library.items[3] = renamed
            rig.clock.instant += 7.hours
            rig.library.saved[renamed.id] = rig.clock.now() - 1.minutes

            rig.runner.run(both)

            assertNotEquals(before, changes())
        }

    @Test
    fun aDeletedItemIsAChange() =
        runBlocking {
            settled()
            val before = changes()
            rig.library.items.removeAt(3)
            rig.clock.instant += 1.hours

            rig.runner.run(openOnly)

            assertNotEquals(before, changes())
        }

    @Test
    fun aNewItemIsAChange() =
        runBlocking {
            settled()
            val before = changes()
            rig.clock.instant += 7.hours
            rig.library.items += SyncWorld.item("new", 1).copy(dateCreated = rig.clock.now())

            rig.runner.run(both)

            assertNotEquals(before, changes())
        }

    @Test
    fun writingTheSamePageAgainChangesNothing() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            val items = (0 until 3).map { SyncWorld.item("m", it) }
            repository.upsertItems(ALICE, MOVIES, items)
            val written = changes()
            assertNotEquals(0L, written)

            repository.upsertItems(ALICE, MOVIES, items)

            assertEquals(written, changes())
        }

    @Test
    fun aCollectionThatIsNewChangedOrGoneIsAChangeAndOneAsStoredIsNot() =
        runBlocking {
            val collections = CollectionRig()
            val repo = collections.repository
            (1..2).forEach { collections.serve(it, *collections.movies(it * 10)) }
            collections.run()
            val first = repo.contentGeneration(ALICE)
            assertNotEquals(0L, first)

            collections.clock.stepMs(30 * 60_000)
            collections.run()
            assertEquals("a pass over what is stored", first, repo.contentGeneration(ALICE))

            collections.clock.stepMs(30 * 60_000)
            collections.saved(2)
            collections.source.members.getValue(collections.box(2)) += collections.movies(99)
            collections.run()
            val edited = repo.contentGeneration(ALICE)
            assertNotEquals(first, edited)

            collections.source.collections.removeAll { it.id == collections.box(1) }
            collections.clock.stepMs(30 * 60_000)
            collections.run()
            assertNotEquals(edited, repo.contentGeneration(ALICE))
            repo.close()
        }

    @Test
    fun aWriteThatRollsBackLeavesTheGenerationAndTheRowsAsTheyWere() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", 0)))
            val before = changes()
            repository.faultBetweenItemsAndCheckpoint = { error("the write died") }

            val failed = runCatching { repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", 1))) }

            assertTrue(failed.isFailure)
            assertEquals("the bump went back with the write", before, changes())
            assertEquals(1, repository.stored(ALICE).size)
        }

    @Test
    fun aCommittedWriteThatChangedRowsRaisesItAndOnlyThatAccountsMovesIt() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            assertEquals(0L, repository.contentGeneration(BOB))

            repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", 0)))

            assertNotEquals(0L, changes())
            assertEquals("another account's generation is its own", 0L, repository.contentGeneration(BOB))
        }

    @Test
    fun theGenerationOnlyGoesUpAndSurvivesReopeningTheSameDatabase() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            val seen = mutableListOf(changes())
            for (n in 0 until 3) {
                repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", n)))
                seen += changes()
            }
            assertEquals(seen.sorted(), seen)
            assertEquals(seen.distinct(), seen)
        }

    @Test
    fun anotherAccountSigningInDropsTheOldOnesGeneration() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", 0)))
            assertNotEquals(0L, changes())

            repository.useAccount(BOB)

            assertEquals("the rows went, so the number starts again", 0L, changes())
        }
}
