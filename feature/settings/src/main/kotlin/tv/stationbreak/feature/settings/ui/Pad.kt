// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.UP_DOWN
import tv.stationbreak.core.ui.padToAct
import tv.stationbreak.core.ui.remoteKeyOf

// The keys of this module's screens, Libraries, Pro and the sign-out question: lists that move up and down.

// The D-pad, Select and Back: the screen keeps all of them, so the focus never leaves it. The media keys are not
// its own.
internal fun isScreenKey(key: Key): Boolean = remoteKeyOf(key) != null

/**
 * A key for a list screen: whether it is the screen's (it keeps the D-pad, Select and Back), acting on [pad]. The
 * shared remote policy (#823) with Up and Down as the pads; Left and Right are kept but do nothing.
 */
internal fun listScreenKey(
    event: KeyEvent,
    pad: (RemoteKey) -> Unit,
): Boolean {
    if (!isScreenKey(event.key)) return false
    padToAct(event.key, event.type, event.nativeKeyEvent.repeatCount, UP_DOWN)?.let(pad)
    return true
}

/**
 * What a key does on a list screen that moves up and down, selects, and ends on Back. Back finishes the screen once: a
 * key that follows it (a fast double Back) is ignored. Left and Right never reach it ([UP_DOWN]) and do nothing.
 */
internal class ListActions(
    private val move: (up: Boolean) -> Unit,
    private val select: () -> Unit,
    private val onDone: () -> Unit,
) {
    private var finished = false

    fun pad(pad: RemoteKey) {
        if (finished) return
        when (pad) {
            RemoteKey.Up -> {
                move(true)
            }

            RemoteKey.Down -> {
                move(false)
            }

            RemoteKey.Select -> {
                select()
            }

            RemoteKey.Back -> {
                finished = true
                onDone()
            }

            RemoteKey.Left, RemoteKey.Right -> {
                Unit
            }
        }
    }
}
