// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [KidsLock] a test sets by hand through [current]: off with no PIN by default. Its PIN is [pin], checked with no
 * counting or lockout (the real lock's counting is `PinKidsLock`'s to test).
 */
class FakeKidsLock(
    initial: KidsLockState = KidsLockState(enabled = false, cap = KidsCap.DEFAULT, pinSet = false),
    var pin: String? = null,
) : KidsLock {
    val current = MutableStateFlow(initial)

    override val state = current

    /** Turns the lock on at [cap] (a PIN counts as set). */
    fun lock(cap: KidsCap = KidsCap.DEFAULT) {
        current.value = KidsLockState(enabled = true, cap = cap, pinSet = true)
    }

    fun unlock() {
        current.value = current.value.copy(enabled = false)
    }

    private fun check(given: String?): PinCheck =
        when (pin) {
            null -> PinResult.NoPin
            given -> PinResult.Ok
            else -> PinResult.Wrong(triesLeft = 1)
        }

    override suspend fun setPin(newPin: String): PinSetup {
        if (pin != null) return PinResult.AlreadySet
        pin = newPin
        current.value = current.value.copy(pinSet = true)
        return PinResult.Ok
    }

    override suspend fun changePin(
        oldPin: String,
        newPin: String,
    ): PinChange =
        when (val checked = check(oldPin)) {
            PinResult.Ok -> PinResult.Ok.also { pin = newPin }
            is PinResult.Wrong -> checked
            else -> PinResult.NoPin
        }

    override suspend fun enable(): Boolean {
        if (pin == null) return false
        current.value = current.value.copy(enabled = true)
        return true
    }

    override suspend fun disable(pin: String): PinCheck = check(pin).also { if (it == PinResult.Ok) unlock() }

    override suspend fun verify(pin: String): PinCheck = check(pin)

    override suspend fun setCap(
        cap: KidsCap,
        pin: String?,
    ): PinCheck {
        val checked = if (current.value.enabled) check(pin) else PinResult.Ok
        if (checked == PinResult.Ok) current.value = current.value.copy(cap = cap)
        return checked
    }
}
