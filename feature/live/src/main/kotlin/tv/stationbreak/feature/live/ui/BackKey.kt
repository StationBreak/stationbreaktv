// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.live.LiveCommand

/**
 * Back, by the shared policy (#1080): [remoteKeyToAct] says Back acts on its release. Its down is only judged: when
 * Back is ours ([keyAction]) the down is consumed and remembered in [held], doing nothing yet, so the activity never
 * sees it and the auto-repeats are consumed too; the release then acts with what the down judged (the overlay may
 * have timed out by then) and is consumed. A down that passed through (the guide is open: Back is its, then the
 * activity's) passes its release too. A release with no down seen here acts as the policy says, on the state it
 * finds: a fast double Back can reach live as a release alone.
 */
@Suppress("LongParameterList") // The key event's parts, kept apart so a test needs no Android KeyEvent.
internal fun handleBack(
    type: KeyEventType,
    key: Key,
    up: OverlayUp,
    held: HeldKeys,
    guide: (open: Boolean) -> Unit,
    atMs: Long?,
    send: (LiveCommand) -> Unit,
): Boolean =
    if (type == KeyEventType.KeyDown) {
        backDown(key, up, held)
    } else {
        val pressed = held.consumedPress(key)
        val passed = held.passedThrough(key)
        held.releases(key, type)
        val action = (pressed ?: keyAction(key, up).takeUnless { passed })?.takeIf { backActs(key, type) }
        action?.let { perform(it, guide, atMs, send) }
        action != null
    }

/** The shared policy's say on whether [key]'s [type] is the Back that acts: its release. */
private fun backActs(
    key: Key,
    type: KeyEventType,
): Boolean = remoteKeyToAct(key, type, 0) == RemoteKey.Back

/** Judges the down of Back: consumed and remembered when Back is ours, passed when it is not. Nothing acts yet. */
private fun backDown(
    key: Key,
    up: OverlayUp,
    held: HeldKeys,
): Boolean {
    held.releases(key, KeyEventType.KeyDown) // A release lost earlier must not outlive this press.
    val action = keyAction(key, up)
    if (action != null) held.hold(key, action) else held.pass(key)
    return action != null
}

/** Does what [action] asks: sends its command, or opens or closes the guide. */
internal fun perform(
    action: KeyAction,
    guide: (open: Boolean) -> Unit,
    atMs: Long?,
    send: (LiveCommand) -> Unit,
) {
    when (action) {
        is KeyAction.Send -> send(action.command.timed(repeat = false, atMs = atMs))
        is KeyAction.Guide -> guide(action.open)
        is KeyAction.Scroll -> Unit
    }
}
