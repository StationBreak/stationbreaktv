// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** A remote key a TV screen takes: the D-pad, Select, and Back. */
enum class RemoteKey { Up, Down, Left, Right, Select, Back }

/** Every direction, for a screen where each one moves something. */
val ALL_DIRECTIONS: Set<RemoteKey> = setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right)

/** Up and Down only, for a list screen where Left and Right are kept but do nothing. */
val UP_DOWN: Set<RemoteKey> = setOf(RemoteKey.Up, RemoteKey.Down)

/** The remote key [key] is, or null for one a screen leaves alone (the media keys, Menu). */
fun remoteKeyOf(key: Key): RemoteKey? =
    when (key) {
        Key.DirectionUp -> RemoteKey.Up
        Key.DirectionDown -> RemoteKey.Down
        Key.DirectionLeft -> RemoteKey.Left
        Key.DirectionRight -> RemoteKey.Right
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteKey.Select
        Key.Back -> RemoteKey.Back
        else -> null
    }

/**
 * The remote policy every TV screen shares (#823): the key to act on for one key event, or null when the screen only
 * keeps it, so the focus never leaves the screen.
 *
 * - Back acts on its release. A fast double Back can reach a screen as a release alone, its down having gone to the
 *   screen behind before this one had the focus; waiting for a down the screen saw would swallow that press.
 * - Select acts on its first press, never on its auto-repeat or its release.
 * - A direction in [pads] acts on every press, auto-repeat included, so holding one scrolls. A direction outside
 *   [pads] is kept but does nothing.
 */
fun remoteKeyToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
    pads: Set<RemoteKey> = ALL_DIRECTIONS,
): RemoteKey? {
    val which = remoteKeyOf(key) ?: return null
    val down = type == KeyEventType.KeyDown
    return when (which) {
        RemoteKey.Back -> which.takeIf { type == KeyEventType.KeyUp }
        RemoteKey.Select -> which.takeIf { down && repeatCount == 0 }
        else -> which.takeIf { down && which in pads }
    }
}

/**
 * The one pad a screen acts on for a key event, or null when the event is only kept: [remoteKeyToAct] with [pads] as
 * the directions that count, [ALL_DIRECTIONS] by default and [UP_DOWN] for a list screen. Every screen's key handler
 * calls this; do not declare another.
 */
fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
    pads: Set<RemoteKey> = ALL_DIRECTIONS,
): RemoteKey? = remoteKeyToAct(key, type, repeatCount, pads)
