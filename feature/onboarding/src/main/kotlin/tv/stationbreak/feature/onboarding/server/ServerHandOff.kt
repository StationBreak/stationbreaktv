// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the [ConnectedServer] from the server screen to sign-in, in memory only (Angua, spec 05 task 2.0).
 *
 * The address and the cleartext acknowledgement must never reach a typed-route argument, a `SavedStateHandle` or
 * `rememberSaveable`, because all three end up in the saved-instance `Bundle`. This holder is a plain field in a
 * process-wide singleton, so it is gone with the process: after process death the sign-in screen finds it empty and
 * falls back to the server screen instead of crashing.
 *
 * It holds one server: there is one server and one session (spec 05, non-goals).
 */
@Singleton
internal class ServerHandOff
    @Inject
    constructor() {
        @Volatile
        private var held: ConnectedServer? = null

        fun put(server: ConnectedServer) {
            held = server
        }

        fun peek(): ConnectedServer? = held

        fun clear() {
            held = null
        }
    }
