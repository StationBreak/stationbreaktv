// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.domain.kids.KidsCap

/**
 * What the Kids Mode PIN pad asks the lock to do once its digits are in (#789). Each is one call, or two for [SetFirst]
 * (set the PIN, then turn the lock on). A PIN in one of these is the viewer's own and is never logged: the classes keep
 * no `toString` of their digits.
 */
internal sealed interface PinSubmit {
    /** Set the first PIN, and turn Kids Mode on with it. */
    class SetFirst(
        val pin: String,
    ) : PinSubmit {
        override fun toString(): String = "SetFirst"
    }

    /** Turn Kids Mode off with [pin]. */
    class TurnOff(
        val pin: String,
    ) : PinSubmit {
        override fun toString(): String = "TurnOff"
    }

    /** Check the PIN as it is ([pin]) before a new one is asked for. */
    class CheckOld(
        val pin: String,
    ) : PinSubmit {
        override fun toString(): String = "CheckOld"
    }

    /** Replace the PIN [old] with [new]. */
    class ChangeTo(
        val old: String,
        val new: String,
    ) : PinSubmit {
        override fun toString(): String = "ChangeTo"
    }

    /** Change the Kids Mode cap to [cap] while the lock is on, with [pin]. */
    class SetCap(
        val cap: KidsCap,
        val pin: String,
    ) : PinSubmit {
        override fun toString(): String = "SetCap"
    }
}
