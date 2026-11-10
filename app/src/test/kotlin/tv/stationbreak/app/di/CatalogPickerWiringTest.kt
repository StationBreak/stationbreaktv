// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId

/**
 * The builder's library picker reads the chosen libraries (spec 26 Part 4, §3), never the playable ones: the picker
 * takes null as "no filter", so a gone selection (nothing playable) must not list every library on the server.
 */
class CatalogPickerWiringTest {
    private val https = ServerAddress(Scheme.Https, "server.example.test", 443)
    private val info = ServerInfo("Synthetic Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val account = LibraryAccount(ServerId("srv1"), UserId("user1"))
    private val chosen = ItemId("library-a")
    private val other = ItemId("library-b")

    @Test
    fun aGoneSelection_listsOnlyTheChosenLibraries_neverAllOfTheServers() =
        runBlocking {
            val server = FakeMediaServer()
            server.librariesResult =
                ServerResult.Success(
                    listOf(
                        Library(chosen, "Library A", LibraryKind.Movies),
                        Library(other, "Library B", LibraryKind.Movies),
                    ),
                )
            val sessions = SessionRepository(InMemorySecretStore(), server)
            sessions.save(session)
            val settings = InMemoryServerSettings(ServerRecord(https))
            assertTrue(settings.saveSelection(StoredSelection(setOf(chosen), seq = 1L, gone = true), setup = true))

            val picker = LineupModule.catalogPicker(InMemoryLibraryRepository(), sessions, server, settings)

            assertEquals(Listed.Ok(listOf(PickerEntry(chosen.value, "Library A"))), picker.libraries(account))
        }
}
