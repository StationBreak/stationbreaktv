// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.FirstSync

/** How the graph reaches the first-sync port: it is the app's, not one screen's, so no ViewModel holds it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FirstSyncEntryPoint {
    fun firstSync(): FirstSync
}
