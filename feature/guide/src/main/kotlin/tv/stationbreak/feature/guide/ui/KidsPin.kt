// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.PinSubmit

// The PIN pad over the Settings sheet (#789): its state and its keys. Drawn by GuideKidsPin.kt. The state is a value
// and the focus on the pad is part of it, as everywhere in the guide: no Compose focus search.

/** How many digits a PIN has. */
internal const val PIN_LENGTH = 4

/**
 * What the pad is for: the first PIN (which turns Kids Mode on), turning Kids Mode off, changing the PIN, or changing
 * the Kids Mode cap while it is on ([Cap]: the pad's [PinEntry.cap] is the cap asked for; the PIN is the current one).
 */
internal enum class PinPurpose { Set, TurnOff, Change, Cap }

/** What the pad asks for now: the PIN as it is ([Current]), a new one ([New]), or the new one again ([Confirm]). */
internal enum class PinStage { Current, New, Confirm }

/** A key of the pad: a digit, Delete, or Forgot PIN? (only while the pad asks for the PIN as it is). */
internal enum class PadKey {
    D1,
    D2,
    D3,
    D4,
    D5,
    D6,
    D7,
    D8,
    D9,
    D0,
    Delete,
    Forgot,
    ;

    /** The digit this key types, or null for Delete and Forgot. */
    val digit: Int? get() = if (this == Delete || this == Forgot) null else (ordinal + 1) % DIGITS
}

// Ten digits, 1 to 9 then 0.
private const val DIGITS = 10

// The pad's rows: two of five digits, then Delete across the foot, then Forgot PIN? where the pad has it.
private val PAD_ROWS =
    listOf(
        listOf(PadKey.D1, PadKey.D2, PadKey.D3, PadKey.D4, PadKey.D5),
        listOf(PadKey.D6, PadKey.D7, PadKey.D8, PadKey.D9, PadKey.D0),
        listOf(PadKey.Delete),
        listOf(PadKey.Forgot),
    )

/** The pad's rows, top to bottom, for drawing: with the Forgot PIN? row when [withForgot]. */
internal fun padRows(withForgot: Boolean): List<List<PadKey>> = if (withForgot) PAD_ROWS else PAD_ROWS.dropLast(1)

// The column Up from a one-key row goes to: the middle of the row above.
private const val FROM_FOOT_COLUMN = 2

/**
 * Where the D-pad's [direction] takes the focus from this key: the neighbour, or this key at an edge. The pad has the
 * Forgot PIN? row below Delete only when [withForgot].
 */
internal fun PadKey.moved(
    direction: RemoteKey,
    withForgot: Boolean = true,
): PadKey {
    val rows = padRows(withForgot)
    val row = rows.indexOfFirst { this in it }
    val column = rows[row].indexOf(this)
    return when (direction) {
        RemoteKey.Left -> {
            rows[row].getOrElse(column - 1) { this }
        }

        RemoteKey.Right -> {
            rows[row].getOrElse(column + 1) { this }
        }

        RemoteKey.Up -> {
            val above = if (rows[row].size == 1) FROM_FOOT_COLUMN else column
            rows.getOrNull(row - 1)?.get(above.coerceAtMost(rows[row - 1].lastIndex)) ?: this
        }

        RemoteKey.Down -> {
            rows.getOrNull(row + 1)?.let { it[column.coerceAtMost(it.lastIndex)] } ?: this
        }

        else -> {
            this
        }
    }
}

/** What the pad says after an answer, in the pad's own terms; the screen gives it its words. */
internal sealed interface PinNotice {
    /** The two entries of a new PIN differ. */
    data object Mismatch : PinNotice

    /** The PIN was wrong, with [triesLeft] more before a lockout. */
    data class Wrong(
        val triesLeft: Int,
    ) : PinNotice

    /** Too many wrong PINs: no PIN is taken for [remainingMillis] more. */
    data class LockedOut(
        val remainingMillis: Long,
    ) : PinNotice

    /** The lock could not be read or written. */
    data object Failed : PinNotice
}

/**
 * The pad as it stands: its [purpose] and [stage], the [digits] typed so far for this step, the pad key under the
 * D-pad, the last answer's [notice] and whether an answer is awaited ([busy]). [old] and [candidate] hold the PINs of
 * earlier steps until the whole is done. A PIN is never logged: [toString] says only how many digits there are.
 */
internal data class PinEntry(
    val purpose: PinPurpose,
    val stage: PinStage,
    val digits: String = "",
    val focus: PadKey = PadKey.D5,
    val notice: PinNotice? = null,
    val busy: Boolean = false,
    val old: String = "",
    val candidate: String = "",
    val cap: KidsCap? = null,
) {
    /** Whether the lock refuses PINs for now. */
    val locked: Boolean get() = notice is PinNotice.LockedOut

    /** Whether the pad offers Forgot PIN?: while it asks for the PIN as it is. */
    val forgettable: Boolean get() = stage == PinStage.Current

    // What the PIN as it is, once typed, is asked for: to turn off, to change the cap, or to check before a new PIN.
    fun currentAsk(digits: String): PinSubmit =
        when {
            purpose == PinPurpose.TurnOff -> PinSubmit.TurnOff(digits)
            purpose == PinPurpose.Cap && cap != null -> PinSubmit.SetCap(cap, digits)
            else -> PinSubmit.CheckOld(digits)
        }

    override fun toString(): String = "PinEntry($purpose, $stage, ${digits.length} digits, busy=$busy)"

    companion object {
        /** The pad for [purpose], at its first step; [cap] is the cap a [PinPurpose.Cap] pad asks for. */
        fun start(
            purpose: PinPurpose,
            cap: KidsCap? = null,
        ): PinEntry {
            val first = if (purpose == PinPurpose.Set) PinStage.New else PinStage.Current
            return PinEntry(purpose, first, cap = cap)
        }
    }
}

// The hardware number keys, in digit order: the digit a key types is its place here.
private val NUMBER_KEYS =
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val PAD_NUMBER_KEYS =
    listOf(
        Key.NumPad0,
        Key.NumPad1,
        Key.NumPad2,
        Key.NumPad3,
        Key.NumPad4,
        Key.NumPad5,
        Key.NumPad6,
        Key.NumPad7,
        Key.NumPad8,
        Key.NumPad9,
    )

// The digit a hardware number key types, or null for any other key.
private fun digitOf(key: Key): Int? {
    val top = NUMBER_KEYS.indexOf(key)
    val pad = PAD_NUMBER_KEYS.indexOf(key)
    return maxOf(top, pad).takeIf { it >= 0 }
}

/**
 * A key while the PIN pad is open on [pin]: the pad has every key of the sheet's. Up, Down, Left and Right move the
 * focus on the pad, OK types the key under it, a number key types its digit and Delete or Backspace takes one back;
 * Back
 * cancels (also while an answer is awaited), back to the sheet on its row. While an answer is awaited, or the lock
 * refuses PINs, no digit is taken. Null for a key that is not the sheet's (the media keys, Menu).
 */
internal fun SettingsPanel.pinKey(
    pin: PinEntry,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    val typed = digitOf(key)
    val once = type == KeyEventType.KeyDown && repeatCount == 0
    return when {
        typed != null -> if (once) press(pin, typed) else SettingsStep(this)
        key == Key.Delete || key == Key.Backspace -> if (once) deleted(pin) else SettingsStep(this)
        remoteKeyOf(key) == null -> null
        else -> pinRemoteKey(pin, remoteKeyToAct(key, type, repeatCount))
    }
}

private fun SettingsPanel.pinRemoteKey(
    pin: PinEntry,
    act: RemoteKey?,
): SettingsStep =
    when (act) {
        RemoteKey.Back -> {
            SettingsStep(copy(pin = null))
        }

        RemoteKey.Select -> {
            when (val key = pin.focus) {
                PadKey.Delete -> deleted(pin)
                PadKey.Forgot -> forgotten(pin)
                else -> press(pin, checkNotNull(key.digit))
            }
        }

        RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right -> {
            SettingsStep(copy(pin = pin.copy(focus = pin.focus.moved(act, pin.forgettable))))
        }

        null -> {
            SettingsStep(this)
        }
    }

// Forgot PIN? opens the reset in the pad's place, and asks for it to start. Open while the lock refuses PINs too: that
// is when it is wanted. Not while an answer is awaited.
private fun SettingsPanel.forgotten(pin: PinEntry): SettingsStep =
    if (pin.busy) SettingsStep(this) else SettingsStep(copy(pin = null, reset = PinReset()), resetStart = true)

private fun SettingsPanel.deleted(pin: PinEntry): SettingsStep =
    if (pin.busy || pin.locked) {
        SettingsStep(this)
    } else {
        SettingsStep(copy(pin = pin.copy(digits = pin.digits.dropLast(1))))
    }

// A digit typed. The last digit of a step finishes it.
private fun SettingsPanel.press(
    pin: PinEntry,
    digit: Int,
): SettingsStep {
    if (pin.busy || pin.locked || pin.digits.length >= PIN_LENGTH) return SettingsStep(this)
    val typed = pin.copy(digits = pin.digits + digit, notice = null)
    return if (typed.digits.length < PIN_LENGTH) SettingsStep(copy(pin = typed)) else finished(typed)
}

// A step's four digits are in: on to the next step, or ask for the answer, or start the new PIN over.
private fun SettingsPanel.finished(pin: PinEntry): SettingsStep {
    val digits = pin.digits
    return when (pin.stage) {
        PinStage.Current -> {
            SettingsStep(copy(pin = pin.copy(busy = true)), pinSubmit = pin.currentAsk(digits))
        }

        PinStage.New -> {
            SettingsStep(copy(pin = pin.copy(stage = PinStage.Confirm, candidate = digits, digits = "")))
        }

        PinStage.Confirm -> {
            if (digits == pin.candidate) {
                val ask =
                    when (pin.purpose) {
                        PinPurpose.Set -> PinSubmit.SetFirst(digits)
                        else -> PinSubmit.ChangeTo(pin.old, digits)
                    }
                SettingsStep(copy(pin = pin.copy(busy = true)), pinSubmit = ask)
            } else {
                val again = pin.copy(stage = PinStage.New, digits = "", candidate = "", notice = PinNotice.Mismatch)
                SettingsStep(copy(pin = again))
            }
        }
    }
}
