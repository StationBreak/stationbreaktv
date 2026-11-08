// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.selection.FirstSyncLibraries
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.Session
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.RoomLibraryRepository
import tv.stationbreak.data.library.SyncVerdict
import kotlin.time.Clock

/**
 * The seeds of the navigation tests, written as the app writes them (spec 26): a sign-in that activates its account in
 * the library cache, a choice the selection's setup stored for that account, and a first sync the worker ran. The
 * library cache is a real database on the device that outlives each test, so every seed starts from a fresh sign-in.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SeedEntryPoint {
    fun accountCache(): AccountCache

    fun roomLibraryRepository(): RoomLibraryRepository
}

private fun seeds(): SeedEntryPoint =
    EntryPointAccessors.fromApplication(
        ApplicationProvider.getApplicationContext<Application>(),
        SeedEntryPoint::class.java,
    )

/** The library account of this session, as the app keys the library cache. */
internal fun Session.account(): LibraryAccount = LibraryAccount(server.id, userId)

/**
 * Stores [session] as the app's sign-in does (spec 06 task 2.12): its account becomes the active one in the library
 * cache. Whatever an earlier test left in that account's cache is forgotten first, as a fresh install would have none.
 */
internal fun signInAsTheAppDoes(
    store: SecretStore,
    server: MediaServer,
    session: Session,
) {
    val cache = seeds().accountCache()
    runBlocking {
        cache.forget(session.account())
        SessionRepository(store, server, cache).save(session)
    }
}

/** Settings with [record] and a choice of [ids], stored as the selection's setup stores it for [owner]: seq 1. */
internal fun choiceOf(
    record: ServerRecord,
    ids: Set<ItemId>,
    owner: LibraryAccount,
): InMemoryServerSettings {
    val settings = InMemoryServerSettings(record)
    val choice = StoredSelection(ids, seq = 1, resetSeq = 1, owner = owner)
    check(runBlocking { settings.saveSelection(choice, setup = true) }) { "the choice was not stored" }
    return settings
}

/** Scripts this server's libraries as empty: a first sync over it lists, counts and walks them, and completes. */
internal fun FakeMediaServer.withEmptyLibraries() {
    libraryItemIdsResult = ServerResult.Success(ItemIdPage(0, 0, emptyList()))
    libraryItemsResult = ServerResult.Success(LibraryItemsPage(0, 0, emptyList()))
    librarySeriesResult = ServerResult.Success(SeriesPage(0, 0, emptyList()))
}

/**
 * Runs the first sync of the stored choice as the app's worker does. The test application supplies no
 * `FirstSyncRunnerProvider`, so WorkManager's worker stops at once in these tests; this is the run it would make. It
 * seeds an earlier run of the app, before this run's start resumes the selection: so it does not wait for that resume,
 * and its library set is the stored choice, written to the cache as the selection's `firstSyncLibraries` writes it.
 */
internal fun runTheFirstSyncAsTheWorkerDoes(
    settings: ServerSettings,
    store: SecretStore,
    server: MediaServer,
) {
    val repository = seeds().roomLibraryRepository()
    val libraries =
        FirstSyncLibraries { account ->
            val chosen = settings.loadLibraries().orEmpty()
            repository.applySelection(account, chosen, nowMs = Clock.System.now().toEpochMilliseconds())
            chosen
        }
    val sessions = SessionRepository(store, server)
    val runner = FirstSyncRunner(settings, sessions, server, repository, libraries, SelectionReady.Complete)
    val verdict = runBlocking { runner.run(attempt = 0) }
    check(verdict == SyncVerdict.Success) { "the first sync ended $verdict" }
}
