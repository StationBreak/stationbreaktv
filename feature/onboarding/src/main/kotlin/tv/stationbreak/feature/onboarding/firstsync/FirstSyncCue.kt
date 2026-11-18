// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/**
 * Stan's cue on the first-sync screen for [phase] (spec 2026-11-18, mapping table): he works the aerial while it runs,
 * and cheers us on the air when it is done.
 */
internal fun firstSyncCue(phase: FirstSyncPhase): StanCue =
    when (phase) {
        FirstSyncPhase.Starting, is FirstSyncPhase.Syncing -> StanCue(StanMood.Busy, R.string.stan_first_sync_busy)
        FirstSyncPhase.WaitingForNetwork -> StanCue(StanMood.Shrug, R.string.stan_first_sync_waiting)
        is FirstSyncPhase.Failed -> StanCue(StanMood.Shrug, R.string.stan_first_sync_failed)
        FirstSyncPhase.Done -> StanCue(StanMood.Cheer, R.string.stan_first_sync_done)
    }
