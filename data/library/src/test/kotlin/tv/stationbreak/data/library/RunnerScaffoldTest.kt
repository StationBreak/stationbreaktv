// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.data.library.SyncWorld.session

/**
 * The account and session handling the three runners share (#654): the run goes ahead only for a restored session whose
 * account is the one signed in; every other way the check ends has its own outcome. Invented accounts only.
 */
class RunnerScaffoldTest {
    private val fake = FakeMediaServer()
    private val sessionLibrary = TestLibraries.inMemory(signedIn = emptyList())
    private val sessions = SessionRepository(InMemorySecretStore(), fake, sessionLibrary)
    private var repository = TestLibraries.inMemory(signedIn = listOf(ALICE))
    private var ran: LibraryAccount? = null

    @After
    fun close() {
        repository.close()
        sessionLibrary.close()
    }

    private val failed: suspend (MediaServerError) -> SyncOutcome = { SyncOutcome.Failed(it.toSyncFailure()) }

    private suspend fun verified(unavailable: suspend (MediaServerError) -> SyncOutcome = failed): SyncOutcome =
        sessions.verifiedRun(session.address, repository, unavailable) { _, account ->
            ran = account
            SyncOutcome.Completed
        }

    private suspend fun signedInSession() {
        sessions.save(session)
        fake.resumeResult = ServerResult.Success(session)
    }

    @Test
    fun aRestoredSessionOfTheActiveAccount_runsTheBlockAndGivesItsOutcome() =
        runBlocking {
            signedInSession()

            assertEquals(SyncOutcome.Completed, verified())
            assertEquals(ALICE, ran)
        }

    @Test
    fun aRestoredSessionOfAnAccountThatIsNotTheActiveOne_isCancelledAndRunsNothing() =
        runBlocking {
            repository.close()
            repository = TestLibraries.inMemory(signedIn = listOf(BOB))
            signedInSession()

            assertEquals(SyncOutcome.Cancelled, verified())
            assertEquals(null, ran)
        }

    @Test
    fun nobodySignedIn_isASessionExpiredFailure() =
        runBlocking {
            assertEquals(SyncOutcome.Failed(SyncFailure.SessionExpired), verified())
            assertEquals(null, ran)
        }

    @Test
    fun aSessionTheServerRejected_isASessionExpiredFailure() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            assertEquals(SyncOutcome.Failed(SyncFailure.SessionExpired), verified())
            assertEquals(null, ran)
        }

    @Test
    fun aServerThatCouldNotBeAsked_isItsFailureByDefault_andTheCallersWhenGiven() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), verified())
            var asked = false
            assertEquals(
                SyncOutcome.Cancelled,
                verified {
                    asked = true
                    SyncOutcome.Cancelled
                },
            )
            assertTrue(asked)
            assertEquals(null, ran)
        }

    @Test
    fun theActiveAccounts_areTheSignedInOnes_andOnlyOneIsTheOnlyActive() =
        runBlocking {
            assertEquals(listOf(ALICE), repository.activeAccounts())
            assertTrue(repository.isOnlyActive(ALICE))
            assertFalse(repository.isOnlyActive(BOB))
        }

    @Test
    fun cancelledOnSignOut_turnsASignOutIntoCancelled_andLeavesOtherOutcomesAlone() =
        runBlocking {
            assertEquals(SyncOutcome.Cancelled, cancelledOnSignOut { throw AccountNotActiveException() })
            assertEquals(SyncOutcome.Completed, cancelledOnSignOut { SyncOutcome.Completed })
        }
}
