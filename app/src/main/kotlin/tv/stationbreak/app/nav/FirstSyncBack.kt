// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState

/**
 * Whether Back stays on the first-sync screen (#1083): true while the first sync runs or is about to, so the viewer
 * does not leave the app by a stray Back (the Fire TV does not). Home still leaves it running in the background.
 *
 * It is false, so Back leaves as the platform does, with nobody signed in (null), after a failure, and once a sync has
 * succeeded. Known gaps: it does not hold while the channels are built (a last success is already saved by then), and
 * it holds on the screen's own 5-minute failure, which the app cannot see.
 */
internal fun LibraryStatus?.backStaysOnFirstSync(): Boolean =
    this != null &&
        lastSuccess == null &&
        (state is SyncState.FirstSyncRunning || state is SyncState.NeverSynced)

/** [backStaysOnFirstSync] for the signed-in account now. Holds Back until the first status arrives. */
@Composable
internal fun rememberBackStaysOnFirstSync(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val stays =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context, FirstSyncEntryPoint::class.java)
                .firstSync()
                .status()
                .map { it.backStaysOnFirstSync() }
        }
    return stays.collectAsState(initial = true)
}
