// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a finished sign-in to the library picker and to "Signed in as", in memory only (spec 05, Unit 4).
 *
 * The session holds the access token, so like [tv.stationbreak.feature.onboarding.server.ServerHandOff] it must never
 * reach a route argument, a `SavedStateHandle` or `rememberSaveable`: all three end up in the saved-instance `Bundle`.
 * A plain field in a process-wide singleton is gone with the process, and a screen that finds it empty goes back to
 * launch routing, which restores the session from the secret store instead.
 *
 * It holds one sign-in: there is one server and one session (spec 05, non-goals). It is cleared when setup is saved,
 * when the user switches user, and when a new server is chosen.
 */
@Singleton
internal class SignedInHandOff
    @Inject
    constructor() {
        @Volatile
        private var held: SignInResult? = null

        @Synchronized
        fun put(result: SignInResult) {
            held = result
        }

        fun peek(): SignInResult? = held

        @Synchronized
        fun clear() {
            held = null
        }

        // A constant: the held session carries the token.
        override fun toString(): String = "SignedInHandOff"
    }
