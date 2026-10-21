// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.playback

import tv.stationbreak.core.model.Session
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in session the media requests ask for, read on every request (`mediaCallFactory`), so a sign-out or a
 * change of server reaches the next call. The app has no process-wide holder of it: sign-in's hand-off is private to
 * onboarding and goes when setup is saved, and the stored session needs a server round trip to restore. So whoever
 * starts playback restores the session and [set]s it here, and [clear]s it on sign-out or when the server refuses it.
 * It lives in memory only and holds the token: [toString] shows nothing.
 */
@Singleton
class CurrentSession
    @Inject
    constructor() {
        private val held = AtomicReference<Session?>(null)

        fun get(): Session? = held.get()

        fun set(session: Session) {
            held.set(session)
        }

        fun clear() {
            held.set(null)
        }

        override fun toString(): String = "CurrentSession"
    }
