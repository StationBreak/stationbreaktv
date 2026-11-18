// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue
import tv.stationbreak.feature.onboarding.trust.trustCue

/** Stan's cue on the server screen for [phase] (spec 2026-11-18, mapping table). */
internal fun serverCue(phase: ServerPhase): StanCue =
    when (phase) {
        ServerPhase.Idle -> StanCue(StanMood.Idle, R.string.stan_server_idle)

        ServerPhase.Testing -> StanCue(StanMood.Busy, R.string.stan_server_testing)

        is ServerPhase.Connected -> StanCue(StanMood.Pleased, R.string.stan_server_found)

        is ServerPhase.Failed, is ServerPhase.InvalidInput -> StanCue(StanMood.Shrug, R.string.stan_server_failed)

        // The viewer refused the certificate: not connected, like any failure.
        ServerPhase.NotTrusted -> StanCue(StanMood.Shrug, R.string.stan_server_failed)

        // Covered by their dialog windows ([serverCovered]), so Stan is not drawn: the cue is the question's.
        is ServerPhase.ConfirmCleartext, is ServerPhase.TrustRequired -> trustCue
    }

/** True while [phase] puts a dialog window over the screen: its keys never reach the screen, so Stan stands down. */
internal fun serverCovered(phase: ServerPhase): Boolean =
    when (phase) {
        is ServerPhase.ConfirmCleartext, is ServerPhase.TrustRequired -> true

        ServerPhase.Idle,
        ServerPhase.NotTrusted,
        ServerPhase.Testing,
        is ServerPhase.InvalidInput,
        is ServerPhase.Failed,
        is ServerPhase.Connected,
        -> false
    }
