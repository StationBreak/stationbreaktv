// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeFirstLineup
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.FirstLineup
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Clock
import kotlin.time.Instant

/** Half an hour into the test day, when [onTheAir]'s channel has a programme on. */
private val halfPast =
    object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(T0 + 30 * MIN)
    }

/** A schedule with channel 7 on the air at [halfPast]. */
internal fun onTheAir(): FakeSchedule = FakeSchedule().also { it.channel(CH7, 7, listOf(program(0, 60, 1))) }

/** The active lineup's channel list as a test drives it: [changed] is a change of the lineup, whatever it holds. */
internal class LineupChanges : ChannelRepository {
    private val lists = MutableSharedFlow<List<ChannelSummary>>(replay = 1).also { it.tryEmit(emptyList()) }

    fun changed() {
        lists.tryEmit(emptyList())
    }

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = lists

    override fun activeChannels(): Flow<List<ChannelSummary>> = lists
}

/**
 * The screen's ViewModel over [firstSync]. The first lineup is [firstLineup]'s (settled at once unless a test says
 * otherwise) and [schedule] has a channel on the air by default, so a test about the sync alone sees the cheer as soon
 * as the sync ends.
 */
internal fun firstSyncViewModel(
    firstSync: FirstSync,
    firstLineup: FirstLineup = FakeFirstLineup(),
    schedule: FakeSchedule = onTheAir(),
    channels: ChannelRepository = LineupChanges(),
): FirstSyncViewModel = FirstSyncViewModel(firstSync, firstLineup, channels, schedule, halfPast)
