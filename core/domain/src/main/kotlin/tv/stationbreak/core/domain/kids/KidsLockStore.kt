// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.Flow

/**
 * A PIN as it is kept: a PBKDF2 [hash] of it under its own random [salt], with the [iterations] it was made with, so a
 * later version can raise them for new PINs. Never the PIN. [toString] shows none of it.
 */
class PinHash(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PinHash && iterations == other.iterations && salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash)

    override fun hashCode(): Int = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations

    override fun toString(): String = "PinHash(redacted)"
}

/**
 * Everything the Kids Mode lock keeps on the device: whether it is [enabled], its [cap], the [pin] (null while none is
 * set), the [failures] counted since the last right PIN, and the lockout those started, as wall-clock milliseconds:
 * when it was started ([lockedAtMs]) and when it ends ([lockedUntilMs]); both 0 when none was.
 */
data class KidsLockRecord(
    val enabled: Boolean = false,
    val cap: KidsCap = KidsCap.DEFAULT,
    val pin: PinHash? = null,
    val failures: Int = 0,
    val lockedAtMs: Long = 0,
    val lockedUntilMs: Long = 0,
) {
    val state: KidsLockState get() = KidsLockState(enabled, cap, pin != null)

    companion object {
        /**
         * What a store gives when its file cannot be read: the lock on at the narrowest cap. It fails closed, and as no
         * PIN can be checked, nothing can turn it off until the file reads again.
         */
        val UNREADABLE: KidsLockRecord = KidsLockRecord(enabled = true, cap = KidsCap.DEFAULT)
    }
}

/**
 * Where the Kids Mode lock is kept (#789): one record on this device, never sent anywhere and never backed up. The
 * lock is its only writer.
 */
interface KidsLockStore {
    /** The stored record and each change of it; [KidsLockRecord.UNREADABLE] when the file cannot be read. */
    val record: Flow<KidsLockRecord>

    /**
     * Replaces the record with [change] of it, atomically, and returns once the new record is on disk. Throws
     * `java.io.IOException` when it could not be read or written; then nothing changed.
     */
    suspend fun update(change: (KidsLockRecord) -> KidsLockRecord): KidsLockRecord
}
