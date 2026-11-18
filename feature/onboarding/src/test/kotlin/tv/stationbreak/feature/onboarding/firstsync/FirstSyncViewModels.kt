// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import tv.stationbreak.core.domain.FirstSync
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

/**
 * The screen's ViewModel over [firstSync]. The first lineup is [firstLineup]'s (settled at once unless a test says
 * otherwise) and [schedule] has a channel on the air by default, so a test about the sync alone sees the cheer as soon
 * as the sync ends.
 */
internal fun firstSyncViewModel(
    firstSync: FirstSync,
    firstLineup: FirstLineup = FakeFirstLineup(),
    schedule: FakeSchedule = onTheAir(),
): FirstSyncViewModel = FirstSyncViewModel(firstSync, firstLineup, schedule, halfPast)
