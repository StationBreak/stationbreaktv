// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct

// The keys of this module's screens, Libraries and the sign-out question: lists that move up and down.

/** A key the screen acts on. */
internal enum class Pad { Up, Down, Select, Back }

// The D-pad, Select and Back: the screen keeps all of them, so the focus never leaves it. The media keys are not
// its own.
internal fun isScreenKey(key: Key): Boolean = remoteKeyOf(key) != null

// The list scrolls up and down only.
private val LIST_PADS = setOf(RemoteKey.Up, RemoteKey.Down)

/**
 * The pad to act on for a key event, or null when the event is only kept: the shared remote policy (#823) with Up and
 * Down as the pads. Left and Right are kept but do nothing.
 */
internal fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): Pad? =
    when (remoteKeyToAct(key, type, repeatCount, LIST_PADS)) {
        RemoteKey.Up -> Pad.Up
        RemoteKey.Down -> Pad.Down
        RemoteKey.Select -> Pad.Select
        RemoteKey.Back -> Pad.Back
        RemoteKey.Left, RemoteKey.Right, null -> null
    }

/** A key for a list screen: whether it is the screen's (it keeps the D-pad, Select and Back), acting on [pad]. */
internal fun listScreenKey(
    event: KeyEvent,
    pad: (Pad) -> Unit,
): Boolean {
    if (!isScreenKey(event.key)) return false
    padToAct(event.key, event.type, event.nativeKeyEvent.repeatCount)?.let(pad)
    return true
}
