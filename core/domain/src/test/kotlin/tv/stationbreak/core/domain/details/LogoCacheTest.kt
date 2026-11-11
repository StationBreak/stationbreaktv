// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory [LogoStore], keyed as a real one is, and counting what the cache asks of it. */
private class MemoryLogoStore : LogoStore {
    val held = mutableMapOf<Pair<String, String>, ByteArray>()
    var retained = 0

    override fun get(
        account: String,
        key: String,
    ) = held[account to key]

    override fun put(
        account: String,
        key: String,
        bytes: ByteArray,
    ) {
        held[account to key] = bytes
    }

    override fun retainOnly(account: String?) {
        retained++
        held.keys.removeAll { it.first != account }
    }
}

@OptIn(SecretAccess::class)
class LogoCacheTest {
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val store = MemoryLogoStore()

    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val token = AccessToken("fake-token-0123")

    private fun sessionOf(user: String) = Session(UserId(user), token, info, address, null, true)

    private fun logo(n: Int) = ItemLogo(ItemId("item$n"), "tag$n")

    private fun TestScope.cache(shared: LogoStore = store) =
        StandardTestDispatcher(testScheduler).let { dispatcher ->
            LogoCache(server, sessions, shared, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
        }

    @Test
    fun aSecondCallIsServedFromDiskNotTheServer() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()

            val first = cache.logo(logo(1))
            val second = cache.logo(logo(1))

            assertContentEquals(FakeMediaServer.LOGO_BYTES, first)
            assertContentEquals(FakeMediaServer.LOGO_BYTES, second)
            assertEquals(1, server.logoCalls.size)
            assertEquals(listOf(LogoCache.LOGO_HEIGHT_PX), server.logoHeights)
        }

    @Test
    fun aNewCacheOverTheSameStoreFindsWhatWasKept() =
        runTest {
            sessions.save(sessionOf("u1"))
            cache().logo(logo(1))
            val again = cache()

            assertContentEquals(FakeMediaServer.LOGO_BYTES, again.logo(logo(1)))
            assertEquals(1, server.logoCalls.size)
        }

    @Test
    fun noSessionIsNoLogoAndNoRequest() =
        runTest {
            assertNull(cache().logo(logo(1)))
            assertEquals(0, server.logoCalls.size)
        }

    @Test
    fun aFailureIsNotKeptAndTheNextCallAsksAgain() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            server.logoResult = ServerResult.Failure(MediaServerError.Timeout())

            assertNull(cache.logo(logo(1)))
            assertTrue(store.held.isEmpty())

            server.logoResult = null
            assertContentEquals(FakeMediaServer.LOGO_BYTES, cache.logo(logo(1)))
            assertEquals(2, server.logoCalls.size)
        }

    @Test
    fun anEmptyAnswerIsNoLogoAndIsNotKept() =
        runTest {
            sessions.save(sessionOf("u1"))
            server.logoResult = ServerResult.Success(ByteArray(0))

            assertNull(cache().logo(logo(1)))
            assertTrue(store.held.isEmpty())
        }

    @Test
    fun concurrentCallersShareOneRequest() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            server.logoGate = gate

            val a = async { cache.logo(logo(1)) }
            val b = async { cache.logo(logo(1)) }
            runCurrent()
            gate.complete(Unit)

            assertContentEquals(FakeMediaServer.LOGO_BYTES, a.await())
            assertContentEquals(FakeMediaServer.LOGO_BYTES, b.await())
            assertEquals(1, server.logoCalls.size)
        }

    @Test
    fun theLastCallerGoingCancelsTheRequestAndKeepsNothing() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            server.logoGate = CompletableDeferred()

            val caller = async { cache.logo(logo(1)) }
            runCurrent()
            assertEquals(1, server.logoCalls.size)

            caller.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(1, server.logoCancelled)
            assertTrue(store.held.isEmpty())
        }

    @Test
    fun oneCallerGoingLeavesTheRequestToTheOther() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            server.logoGate = gate

            val stays = async { cache.logo(logo(1)) }
            val goes = async { cache.logo(logo(1)) }
            runCurrent()
            goes.cancelAndJoin()
            gate.complete(Unit)

            assertContentEquals(FakeMediaServer.LOGO_BYTES, stays.await())
            assertEquals(0, server.logoCancelled)
            assertEquals(1, store.held.size)
        }

    @Test
    fun aCallerThatCameLateStartsItsOwnRequestAfterTheLastOneLeft() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            server.logoGate = CompletableDeferred()
            val first = async { cache.logo(logo(1)) }
            runCurrent()
            first.cancelAndJoin()

            val gate = CompletableDeferred<Unit>()
            server.logoGate = gate
            val second = async { cache.logo(logo(1)) }
            runCurrent()
            gate.complete(Unit)

            assertContentEquals(FakeMediaServer.LOGO_BYTES, second.await())
            assertEquals(2, server.logoCalls.size)
        }

    @Test
    fun anotherAccountNeverSeesWhatWasKept() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            cache.logo(logo(1))
            advanceUntilIdle()

            sessions.save(sessionOf("u2"))
            advanceUntilIdle()
            cache.logo(logo(1))
            advanceUntilIdle()

            // Asked again for the other account, and what u1 had is deleted.
            assertEquals(2, server.logoCalls.size)
            assertEquals(1, store.held.size)
            assertTrue(store.retained >= 1)
        }

    @Test
    fun signOutDeletesTheLogosAndCancelsTheRequestsInFlight() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            cache.logo(logo(1))
            server.logoGate = CompletableDeferred()
            val waiting = async { cache.logo(logo(2)) }
            runCurrent()

            sessions.clear()
            advanceUntilIdle()

            assertNull(waiting.await())
            assertEquals(1, server.logoCancelled)
            assertTrue(store.held.isEmpty())
        }

    @Test
    fun anAnswerThatLandsAfterSignOutIsNotKept() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            server.logoGate = gate
            val waiting = async { cache.logo(logo(1)) }
            runCurrent()

            // The server answers, but the account has gone before the bytes are written.
            sessions.clear()
            gate.complete(Unit)
            advanceUntilIdle()
            waiting.await()

            assertTrue(store.held.isEmpty())
        }

    @Test
    fun clearDeletesEverything() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            cache.logo(logo(1))
            assertEquals(1, store.held.size)

            cache.clear()
            advanceUntilIdle()

            assertTrue(store.held.isEmpty())
        }
}
