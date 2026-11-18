// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/** Stan's cue on the sign-in screen for [phase] (spec 2026-11-18, mapping table). */
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
