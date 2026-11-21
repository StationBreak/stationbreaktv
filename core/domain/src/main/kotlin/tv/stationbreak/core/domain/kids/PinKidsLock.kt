// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** What the lock tells its host, for the log: names only, never a PIN or anything made from one. */
enum class KidsLockEvent {
    PinSet,
    PinChanged,
    Enabled,
    Disabled,
    CapChanged,
    WrongPin,
    LockedOut,
    PinReset,
    StoreFailed,
}

/**
 * When wrong PINs lock PIN entry out: every [freeTries]-th wrong PIN in a row starts a lockout, the first [first] long,
 * each next one twice the last, never longer than [longest].
 */
data class LockoutPolicy(
    val freeTries: Int = 5,
    val first: Duration = 30.seconds,
    val longest: Duration = 1.hours,
) {
    /** The lockout that [failures] wrong PINs in a row start, or null when that many start none. */
    fun lockoutAfter(failures: Int): Duration? {
        if (failures <= 0 || failures % freeTries != 0) return null
        var lockout = first
        var doublings = failures / freeTries - 1
        while (doublings > 0 && lockout < longest) {
            lockout *= 2
            doublings--
        }
        return minOf(lockout, longest)
    }

    /** How many more wrong PINs, after [failures], start the next lockout. */
    fun triesLeft(failures: Int): Int = freeTries - failures % freeTries
}

/**
 * Makes and checks [PinHash]es: PBKDF2 with HMAC-SHA1 (the variant Android has on API 25; the SHA-256 ones came in API
 * 26), [iterations] rounds over a random salt of [SALT_BYTES] bytes, compared in constant time. A four-digit PIN has
 * only 10,000 values, so the hash does not keep the PIN secret from someone who can copy the file: the lockout is the
 * real defence, and it stops guessing on the TV only.
 */
class PinHasher(
    private val iterations: Int = ITERATIONS,
) {
    private val random = SecureRandom()

    fun hash(pin: String): PinHash {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinHash(salt, derive(pin, salt, iterations), iterations)
    }

    /** Whether [pin] is the PIN [stored] was made from, by its own salt and iterations. */
    fun matches(
        pin: String,
        stored: PinHash,
    ): Boolean = MessageDigest.isEqual(derive(pin, stored.salt, stored.iterations), stored.hash)

    private fun derive(
        pin: String,
        salt: ByteArray,
        rounds: Int,
    ): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, rounds, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    companion object {
        const val ITERATIONS: Int = 10_000
        const val SALT_BYTES: Int = 16
        private const val KEY_BITS = 160
        private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    }
}

/** A PIN is exactly four ASCII digits. */
internal fun isPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

private const val PIN_LENGTH = 4

/**
 * The [KidsLock] over a [KidsLockStore] (#789). Its calls take turns on one lock, so two PIN entries never race the
 * count. A PIN attempt is counted on disk before the PIN is checked, and the count cleared after a right one, so
 * stopping the app mid-check cannot win a free try; a store that cannot be written checks nothing
 * ([PinResult.Unavailable]). Lockouts are timed by [clock] (wall time, so they hold across a restart) and by
 * [monotonic] (so they hold against the wall clock being set): within one boot a lockout has the larger of the two
 * remainders left, so a clock set forward does not end it early; a clock set back before a lockout's start starts it
 * again from now, never ends it. A reboot (the monotonic reading went back before the lockout's start) leaves only the
 * wall clock to go by, so a lockout can be ended early by rebooting and setting the clock forward; this is a cabinet
 * lock, not a security boundary. The hash runs in [work], off the caller's thread.
 */
@Suppress("LongParameterList") // The lock's collaborators, each with a default but the store and the two clocks.
class PinKidsLock(
    private val store: KidsLockStore,
    private val clock: Clock,
    private val monotonic: MonotonicClock,
    private val policy: LockoutPolicy = LockoutPolicy(),
    private val hasher: PinHasher = PinHasher(),
    private val work: CoroutineContext = Dispatchers.Default,
    private val report: (KidsLockEvent) -> Unit = {},
) : KidsLock,
    KidsPinReset {
    private val turns = Mutex()

    override val state: Flow<KidsLockState> = store.record.map { it.state }.distinctUntilChanged()

    override suspend fun setPin(newPin: String): PinSetup =
        turns.withLock<PinSetup> {
            if (!isPin(newPin)) return@withLock PinResult.Invalid
            // A lock that is on with no PIN has one it cannot read (a damaged file): no new one may be set over it.
            val record = current()
            if (record.pin != null || record.enabled) return@withLock PinResult.AlreadySet
            val made = withContext(work) { hasher.hash(newPin) }
            saving(PinResult.Unavailable) {
                store.update { it.withoutLockout().copy(pin = made) }
                report(KidsLockEvent.PinSet)
                PinResult.Ok
            }
        }

    override suspend fun changePin(
        oldPin: String,
        newPin: String,
    ): PinChange =
        turns.withLock<PinChange> {
            if (current().pin != null && !isPin(newPin)) return@withLock PinResult.Invalid
            when (val checked = check(oldPin)) {
                PinResult.Ok -> {
                    val made = withContext(work) { hasher.hash(newPin) }
                    saving(PinResult.Unavailable) {
                        store.update { it.copy(pin = made) }
                        report(KidsLockEvent.PinChanged)
                        PinResult.Ok
                    }
                }

                is PinResult.Wrong -> {
                    checked
                }

                is PinResult.LockedOut -> {
                    checked
                }

                PinResult.NoPin -> {
                    PinResult.NoPin
                }

                PinResult.Unavailable -> {
                    PinResult.Unavailable
                }
            }
        }

    override suspend fun resetPin(): Boolean =
        turns.withLock {
            saving(false) {
                store.forgetPin()
                report(KidsLockEvent.PinReset)
                true
            }
        }

    override suspend fun enable(): Boolean =
        turns.withLock {
            val record = current()
            when {
                record.pin == null -> {
                    false
                }

                record.enabled -> {
                    true
                }

                else -> {
                    saving(false) {
                        store.update { it.copy(enabled = it.pin != null) }.enabled.also {
                            report(KidsLockEvent.Enabled)
                        }
                    }
                }
            }
        }

    override suspend fun disable(pin: String): PinCheck =
        turns.withLock<PinCheck> {
            if (!current().enabled) return@withLock PinResult.Ok
            whenRight(pin) {
                store.update { it.copy(enabled = false) }
                report(KidsLockEvent.Disabled)
            }
        }

    override suspend fun verify(pin: String): PinCheck = turns.withLock { check(pin) }

    override suspend fun setCap(
        cap: KidsCap,
        pin: String?,
    ): PinCheck =
        turns.withLock<PinCheck> {
            val change: suspend () -> Unit = {
                store.update { it.copy(cap = cap) }
                report(KidsLockEvent.CapChanged)
            }
            if (!current().enabled) {
                saving(PinResult.Unavailable) {
                    change()
                    PinResult.Ok
                }
            } else {
                // No PIN while the lock is on is a wrong one: the screen asked for none, and it counts.
                whenRight(pin.orEmpty(), change)
            }
        }

    /** Checks [pin] and, when it is right, runs [then]; a failure of either is [PinResult.Unavailable]. */
    private suspend fun whenRight(
        pin: String,
        then: suspend () -> Unit,
    ): PinCheck {
        val checked = check(pin)
        if (checked != PinResult.Ok) return checked
        return saving(PinResult.Unavailable) {
            then()
            PinResult.Ok
        }
    }

    /**
     * Checks [pin] against the stored PIN under the lockout rules. Run only inside [turns]. The attempt is counted on
     * disk first (with the lockout it starts), then the PIN is checked, then a right PIN clears the count.
     */
    private suspend fun check(pin: String): PinCheck =
        saving(PinResult.Unavailable) {
            val record = current()
            val stored = record.pin ?: return@saving PinResult.NoPin
            val now = clock.now().toEpochMilliseconds()
            val elapsed = monotonic.elapsedMillis()
            val remaining = lockedFor(record, now, elapsed)
            if (remaining > 0) return@saving PinResult.LockedOut(remaining)
            val failures = record.failures + 1
            val lockout = policy.lockoutAfter(failures)?.inWholeMilliseconds
            store.update {
                if (lockout == null) {
                    it.copy(failures = failures)
                } else {
                    it.copy(
                        failures = failures,
                        lockedAtMs = now,
                        lockedUntilMs = now + lockout,
                        lockedAtElapsedMs = elapsed,
                    )
                }
            }
            val right = withContext(work) { isPin(pin) && hasher.matches(pin, stored) }
            when {
                right -> {
                    store.update { it.withoutLockout() }
                    PinResult.Ok
                }

                lockout != null -> {
                    report(KidsLockEvent.LockedOut)
                    PinResult.LockedOut(lockout)
                }

                else -> {
                    report(KidsLockEvent.WrongPin)
                    PinResult.Wrong(policy.triesLeft(failures))
                }
            }
        }

    /**
     * How long [record]'s lockout has left at [now] (wall) and [elapsed] (monotonic); 0 when none runs. A clock set
     * back before the lockout's start starts it again from [now] (saved, so a restart keeps it), with its whole length
     * left. Otherwise it is the larger of the wall-clock remainder and, within the boot the lockout started in, the
     * monotonic one (never more than the lockout's length). A monotonic reading before the lockout's start is a
     * reboot: the wall clock alone is used.
     */
    private suspend fun lockedFor(
        record: KidsLockRecord,
        now: Long,
        elapsed: Long,
    ): Long {
        val length = record.lockedUntilMs - record.lockedAtMs
        return when {
            record.lockedUntilMs <= 0 || length <= 0 -> {
                0
            }

            now < record.lockedAtMs -> {
                store.update {
                    it.copy(lockedAtMs = now, lockedUntilMs = now + length, lockedAtElapsedMs = elapsed)
                }
                length
            }

            else -> {
                val wall = (record.lockedUntilMs - now).coerceAtLeast(0)
                val startedAt = record.lockedAtElapsedMs
                val sameBoot = startedAt > 0 && elapsed >= startedAt
                if (sameBoot) maxOf(wall, (length - (elapsed - startedAt)).coerceIn(0, length)) else wall
            }
        }
    }

    private suspend fun current(): KidsLockRecord = store.record.first()

    /** Runs [block]; a store that cannot be read or written gives [failed] instead, with nothing more done. */
    private suspend fun <T> saving(
        failed: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (_: IOException) {
            report(KidsLockEvent.StoreFailed)
            failed
        }
}
