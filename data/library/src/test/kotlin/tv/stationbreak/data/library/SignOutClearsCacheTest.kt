// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session

/**
 * Spec 06 task 2.12, end to end: the real [SessionRepository] over the real database. Signing out clears the
 * signed-out account's saved library, and signing in as another user or on another server clears the old one. It lives
 * here, not in `app`, because this is where Room runs on the JVM; `app` only binds the same two classes.
 */
class SignOutClearsCacheTest {
    private val repository = TestLibraries.inMemory()
    private val server = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server, repository)
    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)

    @After
    fun close() = repository.close()

    private fun session(account: tv.stationbreak.core.domain.LibraryAccount) =
        Session(
            account.user,
            AccessToken("fake-token-for-${account.user.value}"),
            ServerInfo("Home", account.server, ServerVersion(12, 2, 0)),
            address,
            null,
            false,
        )

    private suspend fun fillFor(account: tv.stationbreak.core.domain.LibraryAccount) {
        repository.select(account, setOf(MOVIES))
        repository.writePage(account, MOVIES, listOf(item("one").copy(genres = listOf("g"))), 1, 1, run = 1)
    }

    @Test
    fun signingOutClearsTheSignedOutUsersLibrary() =
        runBlocking {
            sessions.save(session(ALICE))
            fillFor(ALICE)

            sessions.signOut(session(ALICE))

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun signingOutOneUserLeavesAnotherUsersLibraryAlone() =
        runBlocking {
            fillFor(ALICE)
            fillFor(BOB)

            sessions.signOut(session(ALICE))

            assertEquals(emptyList(), repository.snapshot(ALICE, setOf(MOVIES), EPOCH))
            assertEquals(1, repository.snapshot(BOB, setOf(MOVIES), EPOCH).size)
        }

    @Test
    fun signingInAsAnotherUserClearsTheLastUsersLibrary() =
        runBlocking {
            sessions.save(session(ALICE))
            fillFor(ALICE)

            sessions.save(session(BOB))

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun signingInOnAnotherServerClearsTheLibrary() =
        runBlocking {
            sessions.save(session(ALICE))
            fillFor(ALICE)

            sessions.save(session(ALICE_ON_ANOTHER_SERVER))

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun signingInAgainAsTheSameUserKeepsTheLibrary() =
        runBlocking {
            sessions.save(session(ALICE))
            fillFor(ALICE)

            // A session that expired and was signed in again is the same account: its saved library is still good.
            sessions.save(session(ALICE))

            assertEquals(1, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
        }

    @Test
    fun theSessionTheCacheSawNeverReachedTheDatabase() =
        runBlocking {
            sessions.save(session(ALICE))
            fillFor(ALICE)

            // The token is in the secret store only; the cache's schema has no place for it (AccountIsolationTest).
            assertEquals(true, store.snapshot().values.any { it.contains("fake-token-for-alice") })
            assertEquals(
                emptyList<String>(),
                SchemaFile.columnNames().filter { it.contains("token", ignoreCase = true) },
            )
        }
}
