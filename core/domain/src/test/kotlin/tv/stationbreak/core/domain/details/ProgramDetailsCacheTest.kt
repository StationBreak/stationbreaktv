// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(SecretAccess::class)
class ProgramDetailsCacheTest {
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)

    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))

    private val token = AccessToken("fake-token-0123")

    private fun sessionOf(user: String) = Session(UserId(user), token, info, address, null, true)

    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    private fun TestScope.cache(capacity: Int = 200) =
        ProgramDetailsCache(
            server,
            sessions,
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            capacity,
        )

    private fun details(id: CanonicalId) = ItemDetails(ItemId(id.value), "About it.", 2001, listOf("Drama"), null)

    @Test
    fun secondCallIsServedFromMemory() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()

            val first = cache.details(id(1))
            val second = cache.details(id(1))

            assertEquals(details(id(1)), first)
            assertEquals(first, second)
            assertEquals(1, server.itemDetailsCalls.size)
            assertEquals(first, cache.cached(id(1)))
            assertNull(cache.cached(id(2)))
        }

    @Test
    fun failureIsNotCachedAndRetries() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            server.itemDetailsResult = ServerResult.Failure(MediaServerError.Timeout())

            assertNull(cache.details(id(1)))
            assertNull(cache.cached(id(1)))

            server.itemDetailsResult = null
            assertEquals(details(id(1)), cache.details(id(1)))
            assertEquals(2, server.itemDetailsCalls.size)
        }

    @Test
    fun noSessionIsNull() =
        runTest {
            assertNull(cache().details(id(1)))
            assertEquals(0, server.itemDetailsCalls.size)
        }

    @Test
    fun concurrentCallersShareOneRequest() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            server.itemDetailsGate = gate

            val a = async { cache.details(id(1)) }
            val b = async { cache.details(id(1)) }
            runCurrent()
            gate.complete(Unit)

            assertEquals(details(id(1)), a.await())
            assertEquals(details(id(1)), b.await())
            assertEquals(1, server.itemDetailsCalls.size)
        }

    @Test
    fun firstCallerCancelledOthersStillGetResult() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            server.itemDetailsGate = gate

            val first = async { cache.details(id(1)) }
            val second = async { cache.details(id(1)) }
            runCurrent()
            first.cancelAndJoin()
            gate.complete(Unit)

            assertEquals(details(id(1)), second.await())
            assertEquals(1, server.itemDetailsCalls.size)
            assertNotNull(cache.cached(id(1)))
        }

    @Test
    fun otherAccountDoesNotSeeEntries() =
        runTest {
            val cache = cache()
            sessions.save(sessionOf("u1"))
            cache.details(id(1))
            assertNotNull(cache.cached(id(1)))

            sessions.save(sessionOf("u2"))

            assertNull(cache.cached(id(1)))
            cache.details(id(1))
            assertEquals(2, server.itemDetailsCalls.size)
        }

    @Test
    fun evictsBeyondCapacity() =
        runTest {
            sessions.save(sessionOf("u1"))
            val cache = cache(capacity = 2)

            cache.details(id(1))
            cache.details(id(2))
            cache.details(id(1)) // touch: 2 is now the least recently used
            cache.details(id(3))

            assertNotNull(cache.cached(id(1)))
            assertNull(cache.cached(id(2)))
            assertNotNull(cache.cached(id(3)))
        }
}
