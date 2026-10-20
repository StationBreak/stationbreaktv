// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #309: what the lineup refresher learns about the library. A launch sync that finds nothing (the reconcile and
 * the collection pass of every app open) must not look like a change, even though it records a new collection pass and
 * may move the last-success time; a sync that writes anything must. `contentChanges` is that signal.
 */
class ContentChangesTest {
    private val rig = StayCurrentRig()
    private val repository get() = rig.repository
    private val both = SyncPlan(incremental = true, reconcile = true)
    private val openOnly = SyncPlan(incremental = false, reconcile = true)

    @After
    fun close() = rig.close()

    private suspend fun changes() = repository.contentChanges(ALICE).first()

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
            val first = repo.contentChanges(ALICE).first()
            assertNotEquals(0L, first)

            collections.clock.stepMs(30 * 60_000)
            collections.run()
            assertEquals("a pass over what is stored", first, repo.contentChanges(ALICE).first())

            collections.clock.stepMs(30 * 60_000)
            collections.saved(2)
            collections.source.members.getValue(collections.box(2)) += collections.movies(99)
            collections.run()
            val edited = repo.contentChanges(ALICE).first()
            assertNotEquals(first, edited)

            collections.source.collections.removeAll { it.id == collections.box(1) }
            collections.clock.stepMs(30 * 60_000)
            collections.run()
            assertNotEquals(edited, repo.contentChanges(ALICE).first())
            repo.close()
        }

    @Test
    fun theCountStartsAgainAtZeroInANewProcess() =
        runBlocking {
            val fresh = TestLibraries.inMemory(FakeClock(), signedIn = listOf(ALICE))
            assertEquals(0L, fresh.contentChanges(ALICE).first())
            fresh.close()
        }
}
