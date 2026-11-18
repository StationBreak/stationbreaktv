// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.launch.LaunchPhase
import tv.stationbreak.feature.onboarding.server.ServerPhase
import tv.stationbreak.feature.onboarding.signin.SignInPhase

/*
 * Each setup screen's phase as Stan's [StanCue]: the spec's mapping table (2026-11-18). Every `when` is exhaustive,
 * so a new phase does not compile until it has a cue.
 */

/** The cue for a certificate or cleartext question: it is the viewer's call, so Stan only stands by. */
private val Trust = StanCue(StanMood.Idle, R.string.stan_trust)

internal fun welcomeCue(): StanCue = StanCue(StanMood.Idle, R.string.stan_welcome)

internal fun trustCue(): StanCue = Trust

internal fun serverCue(phase: ServerPhase): StanCue =
    when (phase) {
        ServerPhase.Idle -> StanCue(StanMood.Idle, R.string.stan_server_idle)
        ServerPhase.Testing -> StanCue(StanMood.Busy, R.string.stan_server_testing)
        is ServerPhase.Connected -> StanCue(StanMood.Pleased, R.string.stan_server_found)
        is ServerPhase.Failed, is ServerPhase.InvalidInput -> StanCue(StanMood.Shrug, R.string.stan_server_failed)
        ServerPhase.NotTrusted, is ServerPhase.ConfirmCleartext, is ServerPhase.TrustRequired -> Trust
    }

internal fun signInCue(phase: SignInPhase): StanCue =
    when (phase) {
        SignInPhase.Idle, is SignInPhase.SessionEnded -> {
            StanCue(StanMood.Idle, R.string.stan_signin_idle)
        }

        SignInPhase.SigningIn -> {
            StanCue(StanMood.Busy, R.string.stan_signin_busy)
        }

        is SignInPhase.Failed, is SignInPhase.InvalidInput, is SignInPhase.QuickConnectEnded -> {
            StanCue(StanMood.Shrug, R.string.stan_signin_failed)
        }

        is SignInPhase.QuickConnect -> {
            StanCue(StanMood.Idle, R.string.stan_quick_connect)
        }

        // "Again?" would be wrong when the server has Quick Connect off: a shrug, and the screen says why.
        SignInPhase.QuickConnectDisabled -> {
            StanCue(StanMood.Shrug, null)
        }

        // The library picker opens next with the cheer and "YOU'RE IN!", so nothing is said twice.
        SignInPhase.SignedIn -> {
            StanCue(StanMood.Pleased, null)
        }
    }

/** Null while launch routing only checks (Routing, Trusting): that screen is a plain progress line, with no Stan. */
internal fun launchCue(phase: LaunchPhase): StanCue? =
    when (phase) {
        LaunchPhase.Routing, LaunchPhase.Trusting -> null
        is LaunchPhase.Unavailable -> StanCue(StanMood.Shrug, R.string.stan_launch_unavailable)
        is LaunchPhase.Trust -> Trust
    }
