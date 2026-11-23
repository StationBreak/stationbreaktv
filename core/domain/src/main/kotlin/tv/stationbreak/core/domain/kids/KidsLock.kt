// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.Flow

/**
 * How far up the channel numbers Kids Mode reaches (#789): while it is on, only channels numbered 1 to [upTo] can be
 * seen or tuned. The 1 to 99 numbers are the kids channels' alone, so every cap keeps to them.
 */
enum class KidsCap(
    val upTo: Int,
) {
    Kids(KIDS_UP_TO),
    Family(FAMILY_UP_TO),
    Teen(TEEN_UP_TO),
    ;

    companion object {
        /** The cap a new lock starts with, and the one an unreadable stored cap reads as: the narrowest. */
        val DEFAULT: KidsCap = Kids
    }
}

private const val KIDS_UP_TO = 33
private const val FAMILY_UP_TO = 66
private const val TEEN_UP_TO = 99

/**
 * What the Kids Mode lock is now: whether it is [enabled], its [cap], and whether a PIN is set ([pinSet]). It never
 * carries the PIN or anything derived from it.
 */
data class KidsLockState(
    val enabled: Boolean,
    val cap: KidsCap,
    val pinSet: Boolean,
) {
    /**
     * Whether a channel numbered [number] can be seen and tuned: every channel while the lock is off; while it is on,
     * only one numbered 1 to the cap. A channel with no number is hidden then. [floor] is the lowest number the channel
     * may have (`ChannelFloors`, #1097): one numbered below it is hidden too, so a stored lineup not yet moved up (an
     * Adult channel at 40) never shows in a young band. A caller with no channel leaves it at 1.
     */
    fun allows(
        number: Int?,
        floor: Int = 1,
    ): Boolean = !enabled || (number != null && number >= floor && number in 1..cap.upTo)
}

/**
 * What a PIN operation came to. Each operation answers with the narrow type of the cases it can have ([PinCheck],
 * [PinChange], [PinSetup]), so a `when` over it is exhaustive without impossible branches.
 */
sealed interface PinResult {
    /** Done: the PIN was right, or the change was made. */
    data object Ok : PinCheck, PinChange, PinSetup

    /** The PIN was wrong. [triesLeft] more wrong PINs start the next lockout. */
    data class Wrong(
        val triesLeft: Int,
    ) : PinCheck,
        PinChange

    /** Too many wrong PINs: no PIN is checked for [remainingMillis] more milliseconds, right or wrong. */
    data class LockedOut(
        val remainingMillis: Long,
    ) : PinCheck,
        PinChange

    /** No PIN is set, so there is none to check. */
    data object NoPin : PinCheck, PinChange

    /** The new PIN is not four digits. */
    data object Invalid : PinChange, PinSetup

    /** A PIN is set already: it is changed with the old one ([KidsLock.changePin]), never set over. */
    data object AlreadySet : PinSetup

    /** The lock's file could not be read or written, so nothing was checked or changed. */
    data object Unavailable : PinCheck, PinChange, PinSetup
}

/** What checking a PIN can come to ([KidsLock.verify], [KidsLock.disable], [KidsLock.setCap]). */
sealed interface PinCheck : PinResult

/** What changing a PIN can come to ([KidsLock.changePin]). */
sealed interface PinChange : PinResult

/** What setting the first PIN can come to ([KidsLock.setPin]). */
sealed interface PinSetup : PinResult

/**
 * The parental Kids Mode lock (#789): while it is on, only the channels numbered within its cap can be seen or tuned.
 * Turning it on needs a PIN to be set but not entered; turning it off, changing its cap while it is on, and changing
 * the PIN need the PIN. Wrong PINs are counted, and enough of them lock PIN entry out for a while; the count and the
 * lockout survive a restart.
 *
 * It is a cabinet lock, not a security boundary: it stops casual leaving of Kids Mode. A forgotten PIN is cleared only
 * by clearing the app's data. The PIN never appears in a log, a state or a result.
 */
interface KidsLock {
    /** The lock as it is now, and each change of it. Nothing is emitted until the stored lock has been read. */
    val state: Flow<KidsLockState>

    /**
     * Sets the first PIN ([newPin], four digits). [PinResult.AlreadySet] when one is set, and when the lock is on with
     * no PIN it can read (a damaged file), so a new PIN can never be what turns it off.
     */
    suspend fun setPin(newPin: String): PinSetup

    /** Replaces the PIN with [newPin] when [oldPin] is right. A wrong [oldPin] counts as a wrong PIN. */
    suspend fun changePin(
        oldPin: String,
        newPin: String,
    ): PinChange

    /** Turns the lock on, with no PIN entered. False when no PIN is set (and the lock stays off). */
    suspend fun enable(): Boolean

    /** Turns the lock off when [pin] is right. [PinResult.Ok] at once, with nothing checked, when it is already off. */
    suspend fun disable(pin: String): PinCheck

    /** Checks [pin] (for a screen gated by the PIN, such as Channels or Settings), counting a wrong one. */
    suspend fun verify(pin: String): PinCheck

    /**
     * Stores [cap]. While the lock is on this needs the right [pin] (null then counts as a wrong PIN); while it is off
     * [pin] is not looked at.
     */
    suspend fun setCap(
        cap: KidsCap,
        pin: String?,
    ): PinCheck
}
