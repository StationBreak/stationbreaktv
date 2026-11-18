// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.FakeLineupReadiness
import tv.stationbreak.core.domain.lineup.LineupReadiness
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId

/** The account the screen's tests sign in. Synthetic. */
internal val SIGNED_IN = LibraryAccount(ServerId("srv1"), UserId("u"))

/** A saved library with [SIGNED_IN] active, as it is once the first sync has begun. */
internal fun signedInLibrary(): LibraryRepository =
    InMemoryLibraryRepository().also { library -> runBlocking { library.useAccount(SIGNED_IN) } }

/**
 * The screen's ViewModel over [firstSync]. The lineup is [readiness]'s: ready at once unless a test says otherwise, so
 * a test about the sync alone sees the cheer as soon as the sync ends.
 */
internal fun firstSyncViewModel(
    firstSync: FirstSync,
    readiness: LineupReadiness = FakeLineupReadiness(),
    library: LibraryRepository = signedInLibrary(),
): FirstSyncViewModel = FirstSyncViewModel(firstSync, library, readiness)
