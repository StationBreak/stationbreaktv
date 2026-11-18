// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.lineup.FirstLineup
import tv.stationbreak.core.domain.lineup.LineupService

/**
 * Whether the active account's first lineup is still being built (setup's first-sync screen waits on it; live TV
 * stands by on it rather than saying "no channels"): the service's own view of its first choice. Kept apart from
 * [LineupModule] so that a test whose library is scripted (no sync, no refresher, as under `HiltTestApplication`) can
 * script the first lineup too.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object FirstLineupModule {
    @Provides
    fun firstLineup(service: LineupService): FirstLineup = service
}
