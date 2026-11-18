// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue
import tv.stationbreak.feature.onboarding.trust.trustCue

/** Stan's cue on the launch screen; null while routing only checks (Routing, Trusting): a plain progress line. */
internal fun launchCue(phase: LaunchPhase): StanCue? =
    when (phase) {
        LaunchPhase.Routing, LaunchPhase.Trusting -> null
        is LaunchPhase.Unavailable -> StanCue(StanMood.Shrug, R.string.stan_launch_unavailable)
        is LaunchPhase.Trust -> trustCue
    }

/** True while [phase] puts the trust dialog over the screen: its keys never reach the screen. */
internal fun launchCovered(phase: LaunchPhase): Boolean =
    when (phase) {
        is LaunchPhase.Trust -> true
        LaunchPhase.Routing, LaunchPhase.Trusting, is LaunchPhase.Unavailable -> false
    }
