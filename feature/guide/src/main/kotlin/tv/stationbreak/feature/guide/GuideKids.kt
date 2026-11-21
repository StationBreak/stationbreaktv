// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.kids.PinChange
import tv.stationbreak.core.domain.kids.PinCheck
import tv.stationbreak.core.domain.kids.PinResult
import tv.stationbreak.core.domain.kids.PinSetup

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
}

/** A lock that is never on and has no PIN, for a guide built without one (a test's); it refuses every operation. */
internal object NoKidsLock : KidsLock {
    override val state: Flow<KidsLockState> =
        flowOf(KidsLockState(enabled = false, cap = KidsCap.DEFAULT, pinSet = false))

    override suspend fun setPin(newPin: String): PinSetup = PinResult.Unavailable

    override suspend fun changePin(
        oldPin: String,
        newPin: String,
    ): PinChange = PinResult.Unavailable

    override suspend fun enable(): Boolean = false

    override suspend fun disable(pin: String): PinCheck = PinResult.Unavailable

    override suspend fun verify(pin: String): PinCheck = PinResult.Unavailable

    override suspend fun setCap(
        cap: KidsCap,
        pin: String?,
    ): PinCheck = PinResult.Unavailable
}
