// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import tv.stationbreak.core.domain.kids.PinResult

// What the PIN pad does with the lock's answer (#789): the pad's keys are in KidsPin.kt.

/**
 * The answer to what the pad asked for: done (the pad closes, and the lock's own state shows the rest), on to the new
 * PIN after the old one was right, or the pad again with what went wrong said. Ignored when no answer is awaited.
 */
internal fun SettingsPanel.pinAnswered(result: PinResult): SettingsPanel {
    val pin = pin?.takeIf { it.busy } ?: return this
    return when {
        result != PinResult.Ok -> {
            copy(pin = pin.retried(noticeOf(result)))
        }

        pin.purpose == PinPurpose.Change && pin.stage == PinStage.Current -> {
            val next = pin.copy(stage = PinStage.New, old = pin.digits, digits = "", busy = false)
            copy(pin = next.copy(focus = if (pin.focus == PadKey.Forgot) PadKey.D5 else pin.focus))
        }

        else -> {
            copy(pin = null)
        }
    }
}

private fun noticeOf(result: PinResult): PinNotice =
    when (result) {
        is PinResult.Wrong -> PinNotice.Wrong(result.triesLeft)
        is PinResult.LockedOut -> PinNotice.LockedOut(result.remainingMillis)
        else -> PinNotice.Failed
    }

// The same job again after an answer that was not an Ok: the digits are cleared, and a new PIN starts over.
private fun PinEntry.retried(notice: PinNotice): PinEntry {
    val back =
        when {
            stage != PinStage.Confirm -> stage
            purpose == PinPurpose.Change -> PinStage.Current
            else -> PinStage.New
        }
    return copy(stage = back, digits = "", notice = notice, busy = false, old = "", candidate = "")
}

/** The lockout has passed: the pad takes PINs again. */
internal fun SettingsPanel.pinUnlocked(): SettingsPanel {
    val locked = pin?.takeIf { it.locked } ?: return this
    return copy(pin = locked.copy(notice = null))
}
