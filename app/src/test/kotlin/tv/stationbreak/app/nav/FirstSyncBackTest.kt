// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Instant

/** When Back is held on the first-sync screen (#1083). */
class FirstSyncBackTest {
    private val done = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun backStaysWhileTheFirstSyncRuns() {
        assertTrue(LibraryStatus(SyncState.FirstSyncRunning(10, 100), null).backStaysOnFirstSync())
        assertTrue(LibraryStatus(SyncState.FirstSyncRunning(0, null), null).backStaysOnFirstSync())
    }

    @Test
    fun backStaysWhileTheFirstSyncIsAboutToStart() {
        assertTrue(LibraryStatus(SyncState.NeverSynced, null).backStaysOnFirstSync())
    }

    @Test
    fun backLeavesWhenNobodyIsSignedIn() {
        assertFalse((null as LibraryStatus?).backStaysOnFirstSync())
    }

    @Test
    fun backLeavesAfterAFailure() {
        assertFalse(LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), null).backStaysOnFirstSync())
    }

    @Test
    fun backLeavesOnceASyncHasSucceeded() {
        assertFalse(LibraryStatus(SyncState.Idle, done).backStaysOnFirstSync())
        assertFalse(LibraryStatus(SyncState.FirstSyncRunning(900, 1_000), done).backStaysOnFirstSync())
        assertFalse(LibraryStatus(SyncState.NeverSynced, done).backStaysOnFirstSync())
        assertFalse(LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), done).backStaysOnFirstSync())
    }
}
