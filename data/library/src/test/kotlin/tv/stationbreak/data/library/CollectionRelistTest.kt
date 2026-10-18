// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.CollectionCallKind
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedCollectionCall
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 10 task 2.5.4 (Vimes M2, M5; ADR-0015): a full collection re-list is the safety net for change paths the
 * incremental pass cannot see. It lists every BoxSet and re-reads every member list. It runs when `lastRelistMs` is
 * null, ahead of now (the clock went back), or at least the configured interval old (6 h by default), and records its
 * start only on success. A failed collection pass is kept and does not fail the rest of the job.
 */
class CollectionRelistTest {
    private val rig = CollectionRig()
    private val repository get() = rig.repository

    @After
    fun close() = repository.close()

    // A fault hook that times out every call of one kind.
    private fun timeoutFor(
        kind: CollectionCallKind,
        call: PagedCollectionCall,
    ) = if (call.kind == kind) MediaServerError.Timeout() else null

    private suspend fun seed() {
        (1..3).forEach { rig.serve(it, *rig.movies(it * 10)) }
        assertEquals(SyncOutcome.Completed, rig.run().outcome)
        rig.age()
        rig.server.collectionCalls.clear()
    }

    private fun isFull() =
        rig.server.collectionCalls
            .single { it.kind == CollectionCallKind.Listing && it.startIndex == 0 }
            .changedSince == null

    @Test
    fun theFirstPassIsARelist() =
        runBlocking {
            (1..3).forEach { rig.serve(it, *rig.movies(it * 10)) }

            val result = rig.run()

            assertTrue(result.summary.relisted)
            assertEquals(3, result.summary.read)
        }

    @Test
    fun aPassShortOfTheIntervalIsIncremental() =
        runBlocking {
            seed()
            rig.clock.stepMs(5 * 60 * 60_000L + 59 * 60_000L)

            val result = rig.run()

            assertFalse(result.summary.relisted)
            assertFalse(isFull())
            assertEquals(0, result.summary.read)
        }

    @Test
    fun aPassAtTheIntervalIsARelistThatReReadsEveryMemberList() =
        runBlocking {
            seed()
            rig.clock.stepMs(6 * 60 * 60_000L)

            val result = rig.run()

            assertTrue(result.summary.relisted)
            assertTrue(isFull())
            assertEquals(3, result.summary.read)
            assertEquals(3, rig.server.collectionCalls.count { it.kind == CollectionCallKind.Members })
        }

    @Test
    fun aRelistRecordsItsStartAndMovesBothFields() =
        runBlocking {
            seed()
            rig.clock.stepMs(7 * 60 * 60_000L)
            val now = rig.clock.nowMs

            rig.run()

            assertEquals(CollectionSyncEntity("server-1", "alice", now, now), repository.collectionSync(ALICE))
        }

    @Test
    fun anIncrementalPassKeepsTheRelistTime() =
        runBlocking {
            seed()
            val first = repository.collectionSync(ALICE)?.lastRelistMs
            rig.clock.stepMs(30 * 60_000L)

            rig.run()

            assertEquals(first, repository.collectionSync(ALICE)?.lastRelistMs)
        }

    @Test
    fun aClockSetBackBeforeTheLastRelistRelists() =
        runBlocking {
            seed()
            rig.clock.rewindMs(60 * 60_000L)

            val result = rig.run()

            assertTrue(result.summary.relisted)
            assertTrue(isFull())
        }

    @Test
    fun aClockThatJumpedForwardRelists() =
        runBlocking {
            seed()
            rig.clock.stepMs(400.days.inWholeMilliseconds)

            assertTrue(rig.run().summary.relisted)
        }

    @Test
    fun theConfiguredIntervalDecides() =
        runBlocking {
            val short = CollectionRig(relist = 1.hours)
            (1..2).forEach { short.serve(it, *short.movies(it * 10)) }
            short.run()
            short.age()
            short.clock.stepMs(59 * 60_000L)
            assertFalse(short.run().summary.relisted)
            short.clock.stepMs(2 * 60_000L)

            assertTrue(short.run().summary.relisted)
            short.repository.close()
        }

    @Test
    fun aFailedRelistRecordsNothingAndTheNextPassRelistsAgain() =
        runBlocking {
            seed()
            val before = repository.collectionSync(ALICE)
            rig.clock.stepMs(7 * 60 * 60_000L)
            rig.server.respondToCollections = { timeoutFor(CollectionCallKind.Members, it) }

            val failed = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), failed.outcome)
            assertEquals(before, repository.collectionSync(ALICE))
            rig.server.respondToCollections = { null }
            rig.clock.stepMs(10 * 60_000L)

            assertTrue(rig.run().summary.relisted)
        }

    @Test
    fun aRelistFindsAMemberAnIncrementalPassCouldNotSee() =
        runBlocking {
            seed()
            // Changed on the server without bumping the BoxSet (nothing signals a deleted member's removal, say).
            rig.source.members.getValue(rig.box(2)) += rig.movies(99)
            rig.clock.stepMs(30 * 60_000L)
            assertEquals(listOf(rig.film(20).value), rig.run().let { rig.current(2) })
            rig.clock.stepMs(6 * 60 * 60_000L)

            rig.run()

            assertEquals(listOf(rig.film(20).value, rig.film(99).value), rig.current(2))
        }

    @Test
    fun collectionsSyncedIsTrueOnlyOnceARelistSucceeded() =
        runBlocking {
            assertFalse(repository.collectionsSynced(ALICE))
            rig.server.respondToCollections = { timeoutFor(CollectionCallKind.Ids, it) }
            rig.run()
            assertFalse(repository.collectionsSynced(ALICE))
            rig.server.respondToCollections = { null }

            rig.run()

            assertTrue(repository.collectionsSynced(ALICE))
        }

    @Test
    fun theRelistDefaultIsSixHours() {
        assertEquals(6.hours, CollectionRelist().interval)
        assertNull(runCatching { CollectionRelist(0.minutes) }.getOrNull())
    }
}

/** The worker's side (spec 10, 2.5.4): the job runs the pass after the item passes, and keeps its failure. */
class CollectionPassInTheJobTest {
    private val rig = StayCurrentRig()

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)

    @Test
    fun aFailedCollectionPassIsKeptAndDoesNotFailTheJob() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.server.respondToCollections = { MediaServerError.Timeout() }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertEquals(SyncOutcome.Failed(SyncFailure.Network), rig.runner.lastCollectionPass?.outcome)
            assertEquals(
                SyncState.Idle,
                rig.repository
                    .activeStatus()
                    .first()
                    ?.state,
            )
        }

    @Test
    fun theJobRunsTheCollectionPassAfterTheItemPasses() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            rig.runner.run(both)

            assertEquals(SyncOutcome.Completed, rig.runner.lastCollectionPass?.outcome)
            assertTrue(rig.server.collectionCalls.isNotEmpty())
        }

    @Test
    fun theFirstSyncRunsTheFullPassAndSetsCollectionsSynced() =
        runBlocking {
            rig.firstSyncDone()

            assertTrue(rig.repository.collectionsSynced(ALICE))
            assertEquals(CollectionSyncEntity::class, rig.repository.collectionSync(ALICE)!!::class)
        }
}
