// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import tv.stationbreak.core.domain.logName
import tv.stationbreak.feature.live.FlipDirection
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.SeekDirection
import tv.stationbreak.feature.live.TransportView

/** Which overlay is up: the keys mean something else over it (Select, Back), and nothing at all over the guide. */
internal data class OverlayUp(
    /** The actions banner (Info, Last) shows: Select belongs to its buttons. */
    val actions: Boolean = false,
    /** The program info overlay shows. */
    val info: Boolean = false,
    /** The guide is open: it has the D-pad and Select, and Back leaves live TV. */
    val guide: Boolean = false,
    /** The picture is paused: Select plays it, and Left and Right scrub it (with no overlay up). */
    val paused: Boolean = false,
) {
    companion object {
        val None = OverlayUp()
    }
}

/**
 * What is up over the picture for [state] and [info], with the guide [guideOpen] or not, and whether the [transport]
 * says it is paused. A tune banner is nothing.
 */
internal fun overlayUpOf(
    state: LiveState,
    info: LiveInfo?,
    guideOpen: Boolean,
    transport: TransportView? = null,
) = OverlayUp(
    actions = (state as? LiveState.Watching)?.banner?.actions == true,
    info = info != null,
    guide = guideOpen,
    paused = transport?.paused == true,
)

/** What a key asks for: a command for the machine, or the guide opened or closed, which the screen does itself. */
internal sealed interface KeyAction {
    data class Send(
        val command: LiveCommand,
    ) : KeyAction

    data class Guide(
        val open: Boolean,
    ) : KeyAction
}

private fun mappedCommand(
    key: Key,
    up: OverlayUp = OverlayUp.None,
): LiveCommand? {
    // The guide has the D-pad and Select; flipping, Menu and Info would act on a picture the viewer cannot see.
    if (up.guide) return null
    // Left, Right and Select belong to the overlays when one is up; paused with none, they are the transport's.
    val scrubbing = up.paused && !up.actions && !up.info
    return when (key) {
        Key.DirectionUp, Key.ChannelUp -> {
            LiveCommand.Flip(FlipDirection.Up)
        }

        Key.DirectionDown, Key.ChannelDown -> {
            LiveCommand.Flip(FlipDirection.Down)
        }

        Key.Menu -> {
            LiveCommand.Previous
        }

        Key.Info -> {
            LiveCommand.ShowInfo
        }

        // Over the actions banner Select clicks its focused button; over the info overlay it does nothing; paused it
        // plays (ADR-0021, 2).
        Key.DirectionCenter, Key.Enter -> {
            if (scrubbing) LiveCommand.Play else LiveCommand.ShowBanner.takeUnless { up.actions || up.info }
        }

        // Back closes an overlay; with none up it opens the guide (keyAction).
        Key.Back -> {
            LiveCommand.DismissOverlay.takeIf { up.actions || up.info }
        }

        else -> {
            transportCommand(key, scrubbing)
        }
    }
}

/**
 * What [key] does over [up]. Back from the full picture opens the guide, whatever tune banner shows; with the guide
 * open it is not ours, so the activity leaves live TV (spec 17, owner decision 3). The Guide key opens and closes it.
 */
private fun keyAction(
    key: Key,
    up: OverlayUp,
): KeyAction? {
    val command = mappedCommand(key, up)
    return when {
        command != null -> KeyAction.Send(command)
        key == Key.Guide -> KeyAction.Guide(open = !up.guide)
        key == Key.Back && !up.guide -> KeyAction.Guide(open = true)
        else -> null
    }
}

/**
 * The command a key sends the machine on the live screen (spec 15), or null when it sends none. Key-down only,
 * auto-repeat included, so holding Up or Down keeps flipping; a repeat's flip is marked as one, and the machine steps
 * those at its held-key rate (#430). Up/Down and the remote's CH+/CH- flip, Menu goes to the previous channel, Select
 * brings the actions banner (or plays, paused) and Info the program's info (spec 17). The media keys pause, play, and
 * step back and ahead (spec 20), and Left and Right do the same while paused. Back sends only while [up] says an
 * overlay is open
 * (otherwise it opens the guide, which is no command); With the guide open nothing is sent.
 */
internal fun liveKeyCommand(
    event: KeyEvent,
    up: OverlayUp = OverlayUp.None,
): LiveCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    val native = event.nativeKeyEvent
    val command = mappedCommand(event.key, up)
    val repeat = native.repeatCount > 0
    return command?.timed(repeat, native.eventTime)
}

/**
 * The keys whose press [handleLiveKey] consumed and whose release has not come yet. A press changes the screen before
 * its release arrives (Back closes the overlay or opens the guide, Select brings the actions banner and moves the
 * focus to Info), so the release cannot be judged by the state then: it is remembered per press. A release that
 * reached a button instead would click it, since tv-material buttons click on the release, and a Back release that
 * reached the activity would leave live TV. The same goes for the auto-repeat downs of a key held down: they are part
 * of the press, not new ones. It also keeps the presses whose first down passed through untaken, so their repeats keep
 * going where that down went; a repeat of a press whose first down never came here at all is no press of live TV's.
 */
internal class HeldKeys {
    private val consumed = mutableMapOf<Key, KeyAction>()
    private val passed = mutableSetOf<Key>()

    /** What the consumed press of [key], now being held, did; null when [key] is not held after a consumed press. */
    fun consumedPress(key: Key): KeyAction? = consumed[key]

    /** True while any consumed press that flipped (Up, Down, CH+, CH-) is still down. */
    fun flipHeld(): Boolean = consumed.values.any { (it as? KeyAction.Send)?.command is LiveCommand.Flip }

    /** True while any consumed press that sought (a media step key, or Left or Right while paused) is still down. */
    fun seekHeld(): Boolean = consumed.values.any { (it as? KeyAction.Send)?.command is LiveCommand.Seek }

    /** True when [key]'s press, now being held, began with a first down that came here and passed through. */
    fun passedThrough(key: Key): Boolean = key in passed

    /**
     * True for the release of a press that was consumed. Any other event of [key] that is not a repeat ends the hold,
     * so a release lost earlier cannot outlive the next press and swallow its release.
     */
    fun releases(
        key: Key,
        type: KeyEventType,
    ): Boolean {
        val held = type == KeyEventType.KeyUp && key in consumed
        consumed -= key
        passed -= key
        return held
    }

    /** Marks the first down of [key] as seen and left to whatever has the focus. */
    fun pass(key: Key) {
        passed += key
    }

    /** Marks the press of [key], which did [action], as consumed, so its repeats and release are consumed too. */
    fun hold(
        key: Key,
        action: KeyAction,
    ) {
        consumed[key] = action
    }
}

/**
 * Acts on a mapped key and says whether the screen consumed [event]: a mapped key's down (which acts) and its up (which
 * does nothing, but nothing else should see a stray release). Everything else passes. Back is mapped while an overlay
 * is up (it closes it) and from the full picture (it opens the guide through [guide]), never while the guide is open;
 * Select only while no overlay is. The auto-repeats and the release of a press that was consumed are consumed too,
 * whatever [up] says by then. A repeat sends again only when the press flipped or sought, so holding Up keeps
 * flipping; a held
 * Select, Info, Back, Guide or Menu acts once, so a held Back never runs on from the info to the banner to the guide
 * and out of live TV. A repeat never starts anything: one of a press that passed through passes too, and one of a
 * press whose first down never came here (a long OK the guide took and closed on) is consumed, doing nothing.
 */
internal fun handleLiveKey(
    event: KeyEvent,
    up: OverlayUp = OverlayUp.None,
    held: HeldKeys = HeldKeys(),
    guide: (open: Boolean) -> Unit = {},
    send: (LiveCommand) -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    return handleLiveKey(event.type, event.key, up, held, native.repeatCount, guide, native.eventTime, send)
}

/**
 * [handleLiveKey] on the key event's parts. [atMs] is the event's time (`KeyEvent.getEventTime`, uptime), which a flip
 * carries so the machine paces a held key by it. The release of a press that flipped sends [LiveCommand.FlipReleased]:
 * the settle counts from it (#430).
 */
@Suppress("LongParameterList") // The key event's parts, kept apart so a test needs no Android KeyEvent.
internal fun handleLiveKey(
    type: KeyEventType,
    key: Key,
    up: OverlayUp,
    held: HeldKeys,
    repeat: Int = 0,
    guide: (open: Boolean) -> Unit = {},
    atMs: Long? = null,
    send: (LiveCommand) -> Unit,
): Boolean {
    if (type == KeyEventType.KeyDown && repeat > 0) return handleRepeat(key, held, atMs, send)
    val release = release(key, type, held, send)
    if (type == KeyEventType.KeyDown && movesBetweenButtons(key, up)) send(LiveCommand.ShowBanner)
    val action = keyAction(key, up)
    if (action != null && type == KeyEventType.KeyDown) {
        when (action) {
            is KeyAction.Send -> send(action.command.timed(repeat = false, atMs = atMs))
            is KeyAction.Guide -> guide(action.open)
        }
        held.hold(key, action)
    } else if (type == KeyEventType.KeyDown) {
        held.pass(key)
    }
    return release || (action != null && (type == KeyEventType.KeyDown || type == KeyEventType.KeyUp))
}

/**
 * An auto-repeat down of [key]: part of a press already judged, never a new one. The repeats of a consumed press are
 * consumed and send again only when it flipped or sought, marked as repeats so the machine steps at the held-key
 * rate (#430);
 * those of a press that passed through pass too; and those of a press whose first down never came here are consumed
 * and do nothing.
 */
private fun handleRepeat(
    key: Key,
    held: HeldKeys,
    atMs: Long?,
    send: (LiveCommand) -> Unit,
): Boolean {
    val press = held.consumedPress(key)
    val command = (press as? KeyAction.Send)?.command
    if (command is LiveCommand.Flip || command is LiveCommand.Seek) send(command.timed(repeat = true, atMs = atMs))
    return press != null || !held.passedThrough(key)
}

/**
 * True when this event of [key] is the release of a press that was consumed ([HeldKeys.releases]); the release of one
 * that flipped also tells the machine, which settles from it (#430), once no other flip key is still down; so does that
 * of one that sought, once no other seek key is.
 */
private fun release(
    key: Key,
    type: KeyEventType,
    held: HeldKeys,
    send: (LiveCommand) -> Unit,
): Boolean {
    val pressed = held.consumedPress(key)
    val release = held.releases(key, type)
    // With rollover (Up held, Down pressed, Up let go) the flip is still held: only the last flip key's release counts.
    val flipped = (pressed as? KeyAction.Send)?.command is LiveCommand.Flip
    if (release && flipped && !held.flipHeld()) send(LiveCommand.FlipReleased)
    // The same for seeking: the last seek key's release ends it.
    val sought = (pressed as? KeyAction.Send)?.command is LiveCommand.Seek
    if (release && sought && !held.seekHeld()) send(LiveCommand.SeekReleased)
    return release
}

/** Left and Right over the actions banner move the focus between its buttons: the viewer is still at work there. */
private fun movesBetweenButtons(
    key: Key,
    up: OverlayUp,
): Boolean = up.actions && !up.guide && (key == Key.DirectionLeft || key == Key.DirectionRight)

/**
 * The key line of [key] when it is the down of a key that acts (sends a command, or opens or closes the guide), else
 * null: ups and keys that do nothing here are not logged. The guide's keys are logged so a device run shows whether a
 * Guide key reached the app at all (Fire OS takes it on some remotes).
 */
@Suppress("LongParameterList") // The key event's parts, kept apart so a test needs no Android KeyEvent.
internal fun keyLogLine(
    type: KeyEventType,
    key: Key,
    code: Int,
    repeat: Int,
    handled: Boolean,
    state: LiveState,
    up: OverlayUp = OverlayUp.None,
): String? =
    if (type == KeyEventType.KeyDown && keyAction(key, up) != null) {
        liveKeyLine(code, "down", repeat, handled, state)
    } else {
        null
    }

/** The key line: `key code=<keycode> action=<down|up> repeat=<n> handled=<true|false> state=<LiveState class>`. */
internal fun liveKeyLine(
    code: Int,
    action: String,
    repeat: Int,
    handled: Boolean,
    state: LiveState,
): String = "key code=$code action=$action repeat=$repeat handled=$handled state=${state.javaClass.logName()}"
