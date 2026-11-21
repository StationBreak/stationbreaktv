// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.domain.kids.PinRecovery
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct

// The reset of a forgotten Kids Mode PIN (#789): its state and its keys. Drawn by GuideKidsPinReset.kt. The request
// itself runs in GuideDestination, which collects the recovery and hands each state to [SettingsPanel.recovered].

/**
 * The reset as it stands: what the recovery last said ([recovery]), or null while the request is being made. A
 * [PinRecovery.Code] is shown for an administrator to approve; any other state is the last.
 */
internal data class PinReset(
    val recovery: PinRecovery? = null,
) {
    /** Whether the request has ended, one way or another. */
    val finished: Boolean get() = recovery != null && recovery !is PinRecovery.Code
}

/**
 * A key while the reset is open on [reset]: Back cancels, and closes it to the sheet on its row (the request is
 * stopped by the screen, which sees the reset gone). OK closes a finished reset that worked, and asks again after one
 * that did not (a new code). Every other remote key is kept and does nothing. Null for a key that is not the sheet's.
 */
internal fun SettingsPanel.resetKey(
    reset: PinReset,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? =
    when {
        remoteKeyOf(key) == null -> {
            null
        }

        remoteKeyToAct(key, type, repeatCount) == RemoteKey.Back -> {
            SettingsStep(copy(reset = null))
        }

        remoteKeyToAct(key, type, repeatCount) != RemoteKey.Select || !reset.finished -> {
            SettingsStep(this)
        }

        reset.recovery == PinRecovery.Done -> {
            SettingsStep(copy(reset = null))
        }

        else -> {
            SettingsStep(copy(reset = PinReset()), resetStart = true)
        }
    }

/** The recovery said [state]: the reset shows it. Ignored once the reset is closed. */
internal fun SettingsPanel.recovered(state: PinRecovery): SettingsPanel {
    val open = reset ?: return this
    return copy(reset = open.copy(recovery = state))
}
