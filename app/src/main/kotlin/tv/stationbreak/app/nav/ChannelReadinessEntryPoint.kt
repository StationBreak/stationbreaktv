// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.lineup.ChannelReadiness

/** How the screen reaches the app-wide readiness: it is not a screen's own, so no ViewModel holds it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ChannelReadinessEntryPoint {
    fun readiness(): ChannelReadiness
}
