// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.ServerAddress

/** A server whose Quick Connect can be made to wait for ever (the user has not approved yet). */
class WaitingServer(
    private val inner: FakeMediaServer,
) : MediaServer by inner {
    var waits = false

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> =
        if (waits) {
            flow {
                emit(QuickConnectState.CodeIssued("111111"))
                awaitCancellation()
            }
        } else {
            inner.quickConnect(address, acknowledgement)
        }
}

/** Settings that can run a hook just before the library choice is stored, to cancel or fail the run there. */
class HookedSettings(
    private val inner: InMemoryServerSettings,
) : ServerSettings by inner {
    var beforeSaveLibraries: () -> Unit = {}

    override suspend fun saveLibraries(ids: Set<ItemId>): Boolean {
        beforeSaveLibraries()
        return inner.saveLibraries(ids)
    }
}

/** What the BenchSetup tests share: a fake server, in-memory stores, and the lines the activity would log. */
open class SetupFixture {
    protected val session = BenchTestData.session
    protected val address = session.address
    protected val fake = FakeMediaServer()
    protected val store = InMemorySecretStore()
    protected val sessions = SessionRepository(store, fake)
    protected val settings = InMemoryServerSettings()
    protected val hooked = HookedSettings(settings)
    protected val waiting = WaitingServer(fake)
    protected val lines = mutableListOf<String>()
    protected val setup = BenchSetup(waiting, sessions, hooked, store, lines::add)

    protected val libraries =
        listOf(
            Library(ItemId("films"), "Films", LibraryKind.Movies),
            Library(ItemId("home"), "Home", LibraryKind.HomeVideos),
        )

    protected fun approvedSignIn() {
        fake.discoverResult = ServerResult.Success(ResolvedServer(address, session.server))
        fake.quickConnectStates =
            listOf(
                QuickConnectState.CodeIssued("123456"),
                QuickConnectState.Waiting,
                QuickConnectState.SignedIn(session),
            )
        fake.librariesResult = ServerResult.Success(libraries)
    }

    protected fun stored() = store.snapshot()[SessionRepository.SESSION_KEY]

    protected suspend fun signedInDevice() {
        sessions.save(session)
        settings.save(ServerRecord(address))
    }
}
