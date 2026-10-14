// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignOutTest {
    private val store = InMemorySecretStore()
    private val server = FakeMediaServer()
    private val repository = SessionRepository(store, server)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val session =
        Session(UserId("u"), AccessToken("fake-token"), info, address, null, false)

    @Test
    fun aCancelledCallerStillClearsAndStillFinishesTheRevoke() =
        runBlocking {
            repository.save(session)
            val gate = CompletableDeferred<Unit>()
            server.revokeGate = gate
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.signOut(session) }
            job.cancel()
            // Cancellation doesn't reach the revoke: the caller is still waiting on it, and the session is gone.
            assertTrue(!job.isCompleted)
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            gate.complete(Unit)
            server.revokeFinished.await()
            job.join()
            assertEquals(1, server.revokeCalls.size)
        }

    @Test
    fun aCancelledCallerFinishesAfterTheCancelRequest() =
        runBlocking {
            repository.save(session)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.signOut(session) }
            job.cancelAndJoin()
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            assertEquals(listOf(session), server.revokeCalls)
        }
}
