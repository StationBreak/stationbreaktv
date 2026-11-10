// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Task 4.5 (Part 4 §4 "StayCurrent otherwise", D11, D12), beside [StayCurrentRunnerTest]: after passes that
 * completed, or an incremental pass that completed in a run not cancelled, a run asks the selection to expire the holds
 * that ended, then hands it the run's one listing to follow; the rewalk on that listing needs the whole run completed.
 * While the selection is gone it makes no pass and still lists. None of it changes how the run ended. Invented
 * libraries and names only.
 */
class StayCurrentAfterPassesTest {
    private val lines = mutableListOf<String>()
    private val rig = StayCurrentRig { lines += it }
    private val server = rig.server

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)

    // Spec 26, Task 4.5 (D12, §4 "StayCurrent otherwise"): after passes that completed, the ended holds expire, then
    // the run's one listing of the server's libraries is handed to the selection to follow.
    @Test
    fun afterPassesThatCompleted_holdsExpire_thenTheRunsListingIsFollowed() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            assertEquals(SyncOutcome.Completed, rig.runner.outcome(both))

            assertEquals(listOf("expire", "follow"), rig.asked)
            val listed = rig.followed.single() as ServerResult.Success
            assertEquals(listOf(MOVIES), listed.value.map { it.id })
            assertNotNull("the rewalk ran on it", rig.runner.lastRewalk)
        }

    // Fix round 1 (arch MED-1): an incremental pass that completed recorded the success the clock guard needs, so a
    // reconcile that fails after it does not stop the expiry or the listing. The rewalk needs the whole run.
    @Test
    fun anIncrementalThatCompleted_thenAFailedReconcile_stillExpiresAndFollows_butDoesNotRewalk() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (call.idsOnly) MediaServerError.Timeout() else null }

            val outcome = rig.runner.outcome(both)

            assertTrue(outcome is SyncOutcome.Failed)
            assertEquals(listOf("expire", "follow"), rig.asked)
            assertNull("no rewalk", rig.runner.lastRewalk)
        }

    // A reconcile-only run (the usual open) that completed follows its listing and rewalks on it; the expiry is asked
    // for too (the cache's clock guard decides whether it may act).
    @Test
    fun aReconcileOnlyRunThatCompleted_followsItsListing_andRewalks() =
        runBlocking {
            rig.firstSyncDone()

            assertEquals(SyncOutcome.Completed, rig.runner.outcome(SyncPlan(incremental = false, reconcile = true)))

            assertEquals(listOf("expire", "follow"), rig.asked)
            assertNotNull(rig.runner.lastRewalk)
        }

    // Fix round 1 (D4): a playable set that is empty but not null is not "gone", and is said apart.
    @Test
    fun theRewalk_withAnEmptyPlayableSet_isSkippedForItsOwnReason() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(SyncWorld.session.address), emptySet())
            val rewalk = Rewalk(rig.repository, server, rig.clock) {}
            val steps = SelectionSteps(settings, NO_LISTINGS, NO_EXPIRY, rewalk) { lines += it }

            val walked = steps.rewalk(SyncWorld.session, ALICE, ServerResult.Success(listOf(SyncWorld.movies)))

            assertNull(walked)
            assertTrue(lines.any { "RewalkSkipped reason=NoLibraries" in it })
            assertFalse(lines.any { "reason=Gone" in it })
        }

    @Test
    fun passesThatDidNotComplete_neitherExpireHoldsNorFollowTheListing() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { MediaServerError.Timeout() }

            rig.runner.run(both)

            assertEquals(emptyList<String>(), rig.asked)
        }

    // D11: while the selection is gone the passes (and so the rewalk) are skipped, and the server is still listed, so
    // a chosen library that comes back ends the gone state.
    @Test
    fun whileTheSelectionIsGone_thePassesAreSkipped_andTheListingIsStillFollowed() =
        runBlocking {
            rig.firstSyncDone()
            rig.goneSelection()
            rig.clock.instant += 7.hours
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess

            rig.runner.run(both)

            assertTrue("no pass ran", server.calls.isEmpty())
            assertEquals("listed, and no hold expiry without a pass", listOf("follow"), rig.asked)
            assertTrue(rig.followed.single() is ServerResult.Success)
            val status = rig.repository.activeStatus().first()
            assertEquals(recorded, status?.lastSuccess)
            assertTrue(lines.any { "reason=Gone" in it })
        }

    // A step after the passes that throws is reported by its absence and never changes how the run ended: the next
    // step still runs.
    @Test
    fun anAfterPassStepThatThrows_neverChangesHowTheRunEnded() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.onExpire = { error("the expiry failed") }

            assertEquals(SyncOutcome.Completed, rig.runner.outcome(both))

            assertEquals(listOf("expire", "follow"), rig.asked)
            val state =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.state
            assertFalse("not recorded as a failure", state is SyncState.LastSyncFailed)
        }

    // #654 follow-up: a sign-out that lands in the after-passes hook does not turn the run that completed into a
    // cancelled one; the hook's own work (here the listing's follow) is skipped, as for the backfill.
    @Test
    fun aSignOutDuringTheAfterPassesHook_leavesTheRunCompleted() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.onExpire = { throw AccountNotActiveException() }

            assertEquals(SyncOutcome.Completed, rig.runner.outcome(both))

            assertEquals("the follow never ran", listOf("expire"), rig.asked)
        }

    // #654 follow-up: a Sync now press hands the run the account its listing was made for. Another account's listing
    // is not walked on (the run's own account is what the rewalk writes to), and the run is not failed for it.
    @Test
    fun aListingForAnotherAccount_isNotRewalked_andTheRunStillCompletes() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val listed = ServerResult.Success(listOf(SyncWorld.movies))

            val outcome = rig.runner.runWaiting(both, followServer = false, listed = listed, listedFor = BOB)

            assertEquals(SyncOutcome.Completed, outcome)
            assertNull("no rewalk on another account's listing", rig.runner.lastRewalk)
            assertTrue(lines.any { "ListingAccountMismatch" in it })
        }

    @Test
    fun aListingForTheRunsOwnAccount_isRewalked() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val listed = ServerResult.Success(listOf(SyncWorld.movies))

            val outcome = rig.runner.runWaiting(both, followServer = false, listed = listed, listedFor = ALICE)

            assertEquals(SyncOutcome.Completed, outcome)
            assertNotNull(rig.runner.lastRewalk)
        }

    // Spec 26, Task 4.5, through the real selection: an item owned by a held library and listed by a chosen one stays
    // with its held owner, and the expiry after the next completed pass moves it to the chosen lister.
    @Test
    fun heldOwner_keepsRows_thenExpiryMovesToChosen() =
        runBlocking {
            rig.firstSyncDone()
            val held = ItemId(hexId(0xB0F0))
            val repository = rig.repository
            repository.applySelection(ALICE, setOf(MOVIES, held), nowMs = rig.clock.nowMs)
            repository.put(ALICE, held, sitem(1, library = held))
            repository.put(ALICE, MOVIES, sitem(1))
            val holdMs = 10_000L
            val holds = mapOf(held to rig.clock.nowMs + holdMs)
            repository.applySelection(ALICE, setOf(MOVIES), holds = holds, nowMs = rig.clock.nowMs)
            val selection = selectionOver(repository, rig.settings, rig.server, rig.clock)
            selection.resumeAtStart()
            assertEquals("a held owner keeps the item", held, repository.ownerOf(ALICE, 1))
            val runner =
                StayCurrentRunner(
                    rig.settings,
                    rig.sessions,
                    rig.server,
                    repository,
                    SelectionReady.Complete,
                    selection,
                    selection,
                    rig.clock,
                )
            rig.clock.stepMs(holdMs)

            // The incremental pass only: a reconcile would read the invented item as gone from the server.
            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan(incremental = true, reconcile = false)))

            assertEquals("the expiry moves it to the chosen lister", MOVIES, repository.ownerOf(ALICE, 1))
            assertTrue(repository.alsoListed(ALICE).isEmpty())
            assertTrue(repository.movedVersioned(ALICE, 1, held))
            val rows = repository.dao.libraries("server-1", "alice")
            assertNull("the held library is gone", rows.find { it.libraryId == held.value })
        }
}
