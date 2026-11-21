// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PinKidsLockTest {
    private class FakeClock(
        var ms: Long = 1_700_000_000_000L,
    ) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)

        fun advance(by: Duration) {
            ms += by.inWholeMilliseconds
        }
    }

    private val clock = FakeClock()
    private val store = InMemoryKidsLockStore()

    // Few iterations: the tests check behaviour, not cost.
    private val fastHasher = PinHasher(iterations = 10)

    private fun lock(on: KidsLockStore = store): KidsLock {
        // The hash runs on the caller's thread: the tests check behaviour, not where it runs.
        return PinKidsLock(on, clock, hasher = fastHasher, work = EmptyCoroutineContext)
    }

    private fun KidsLock.now() = runBlocking { state.first() }

    private suspend fun KidsLock.wrong(times: Int): PinCheck {
        var last: PinCheck = PinResult.Ok
        repeat(times) { last = verify("0000") }
        return last
    }

    @Test
    fun aNewLockIsOffAtTheKidsCapWithNoPin() {
        assertEquals(KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = false), lock().now())
    }

    @Test
    fun theCapsReachThirtyThreeSixtySixAndNinetyNine() {
        assertEquals(listOf(33, 66, 99), KidsCap.entries.map { it.upTo })
        assertEquals(KidsCap.Kids, KidsCap.DEFAULT)
    }

    @Test
    fun aPinIsFourDigitsAndNothingElse() =
        runBlocking {
            val lock = lock()
            for (bad in listOf("", "123", "12345", "12a4", "١٢٣٤", " 1234", "12 4")) {
                assertEquals(PinResult.Invalid, lock.setPin(bad), bad)
            }
            assertFalse(lock.now().pinSet)
            assertEquals(PinResult.Ok, lock.setPin("0042"))
            assertTrue(lock.now().pinSet)
        }

    @Test
    fun aSetPinIsNeverSetOver() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            assertEquals(PinResult.AlreadySet, lock.setPin("5678"))
            assertEquals(PinResult.Ok, lock.verify("1234"))
        }

    @Test
    fun thePinIsKeptOnlyAsASaltedHash() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            val kept = checkNotNull(store.current.value.pin)
            assertFalse(kept.hash.contentEquals("1234".toByteArray()))
            assertFalse(String(kept.hash, Charsets.ISO_8859_1).contains("1234"))
            assertFalse(
                store.current.value
                    .toString()
                    .contains("1234"),
            )
            assertEquals(PinHasher.SALT_BYTES, kept.salt.size)
            // A second store with the same PIN gets another salt, so another hash.
            val other = InMemoryKidsLockStore()
            lock(other).setPin("1234")
            val second = checkNotNull(other.current.value.pin)
            assertFalse(kept.salt.contentEquals(second.salt))
            assertFalse(kept.hash.contentEquals(second.hash))
        }

    @Test
    fun theDefaultHasherUsesPbkdf2WithTheStoredIterations() {
        val hasher = PinHasher()
        val made = hasher.hash("2468")
        assertEquals(PinHasher.ITERATIONS, made.iterations)
        assertTrue(hasher.matches("2468", made))
        assertFalse(hasher.matches("2469", made))
        // A hash made with other iterations is checked with its own.
        assertTrue(hasher.matches("2468", PinHasher(iterations = 3).hash("2468")))
    }

    @Test
    fun turningOnNeedsAPinSetButNotEntered() =
        runBlocking {
            val lock = lock()
            assertFalse(lock.enable())
            assertFalse(lock.now().enabled)
            lock.setPin("1234")
            assertTrue(lock.enable())
            assertTrue(lock.now().enabled)
        }

    @Test
    fun turningOffNeedsTheRightPin() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            lock.enable()
            assertEquals(PinResult.Wrong(triesLeft = 4), lock.disable("4321"))
            assertTrue(lock.now().enabled)
            assertEquals(PinResult.Ok, lock.disable("1234"))
            assertFalse(lock.now().enabled)
            // Already off: nothing to check.
            assertEquals(PinResult.Ok, lock.disable("9999"))
        }

    @Test
    fun verifyWithNoPinSaysSo() =
        runBlocking {
            assertEquals(PinResult.NoPin, lock().verify("1234"))
            assertEquals(0, store.current.value.failures)
        }

    @Test
    fun changingThePinNeedsTheOldOne() =
        runBlocking {
            val lock = lock()
            assertEquals(PinResult.NoPin, lock.changePin("1234", "5678"))
            lock.setPin("1234")
            assertEquals(PinResult.Wrong(triesLeft = 4), lock.changePin("1111", "5678"))
            assertEquals(PinResult.Invalid, lock.changePin("1234", "56789"))
            assertEquals(PinResult.Ok, lock.changePin("1234", "5678"))
            assertEquals(PinResult.Ok, lock.verify("5678"))
            assertEquals(PinResult.Wrong(triesLeft = 4), lock.verify("1234"))
        }

    @Test
    fun theCapChangesFreelyWhileOffAndOnlyWithThePinWhileOn() =
        runBlocking {
            val lock = lock()
            assertEquals(PinResult.Ok, lock.setCap(KidsCap.Teen, null))
            assertEquals(KidsCap.Teen, lock.now().cap)
            lock.setPin("1234")
            lock.enable()
            assertEquals(PinResult.Wrong(triesLeft = 4), lock.setCap(KidsCap.Family, null))
            assertEquals(PinResult.Wrong(triesLeft = 3), lock.setCap(KidsCap.Family, "9999"))
            assertEquals(KidsCap.Teen, lock.now().cap)
            assertEquals(PinResult.Ok, lock.setCap(KidsCap.Family, "1234"))
            assertEquals(KidsCap.Family, lock.now().cap)
        }

    @Test
    fun fiveWrongPinsLockOutForThirtySecondsEvenTheRightOne() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            assertEquals(PinResult.Wrong(triesLeft = 1), lock.wrong(4))
            assertEquals(PinResult.LockedOut(30_000), lock.verify("0000"))
            clock.advance(10.seconds)
            assertEquals(PinResult.LockedOut(20_000), lock.verify("1234"))
            clock.advance(20.seconds)
            assertEquals(PinResult.Ok, lock.verify("1234"))
        }

    @Test
    fun aLockoutCountsNoAttempt() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            lock.wrong(5)
            repeat(20) { lock.verify("0000") }
            assertEquals(5, store.current.value.failures)
        }

    @Test
    fun theLockoutDoublesForEachFiveMoreWrongPinsUpToAnHour() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            val seen = mutableListOf<Long>()
            repeat(10) {
                val locked = lock.wrong(5) as PinResult.LockedOut
                seen += locked.remainingMillis
                clock.advance(locked.remainingMillis.milliseconds)
            }
            val expected =
                listOf(30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes, 16.minutes, 32.minutes)
                    .map { it.inWholeMilliseconds } + List(3) { 1.hours.inWholeMilliseconds }
            assertEquals(expected, seen)
        }

    @Test
    fun theRightPinClearsTheCount() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            lock.wrong(4)
            assertEquals(PinResult.Ok, lock.verify("1234"))
            assertEquals(PinResult.Wrong(triesLeft = 4), lock.verify("0000"))
        }

    @Test
    fun theCountAndTheLockoutSurviveANewLockOnTheSameStore() =
        runBlocking {
            lock().apply {
                setPin("1234")
                wrong(3)
            }
            // A relaunch: a fresh lock over what is kept.
            val relaunched = lock()
            assertEquals(PinResult.Wrong(triesLeft = 1), relaunched.verify("0000"))
            assertEquals(PinResult.LockedOut(30_000), relaunched.verify("0000"))
            clock.advance(5.seconds)
            assertEquals(PinResult.LockedOut(25_000), lock().verify("1234"))
        }

    @Test
    fun aClockSetBackStartsTheLockoutAgainRatherThanEndingIt() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            lock.wrong(5)
            clock.advance((-1).hours)
            assertEquals(PinResult.LockedOut(30_000), lock.verify("1234"))
            clock.advance(29.seconds)
            assertEquals(PinResult.LockedOut(1_000), lock.verify("1234"))
            clock.advance(1.seconds)
            assertEquals(PinResult.Ok, lock.verify("1234"))
        }

    @Test
    fun anAttemptIsCountedOnDiskBeforeThePinIsChecked() =
        runBlocking {
            val counted = mutableListOf<Int>()
            val watching =
                object : KidsLockStore by store {
                    override suspend fun update(change: (KidsLockRecord) -> KidsLockRecord): KidsLockRecord =
                        store.update(change).also { counted += it.failures }
                }
            val lock = lock(watching)
            lock.setPin("1234")
            counted.clear()
            lock.verify("1234")
            // Counted (1), then cleared (0) by the right PIN.
            assertEquals(listOf(1, 0), counted)
        }

    @Test
    fun aStoreThatCannotBeWrittenChecksNothing() =
        runBlocking {
            val lock = lock()
            lock.setPin("1234")
            lock.enable()
            store.failWrites = true
            assertEquals(PinResult.Unavailable, lock.disable("1234"))
            assertEquals(PinResult.Unavailable, lock.verify("1234"))
            assertEquals(PinResult.Unavailable, lock.changePin("1234", "5678"))
            store.failWrites = false
            assertTrue(lock.now().enabled)
        }

    @Test
    fun anUnreadableStoreReadsAsLockedWithNoWayOff() =
        runBlocking {
            val unreadable = InMemoryKidsLockStore(KidsLockRecord.UNREADABLE)
            val lock = lock(unreadable)
            assertEquals(KidsLockState(enabled = true, cap = KidsCap.Kids, pinSet = false), lock.now())
            assertEquals(PinResult.NoPin, lock.disable("1234"))
            // Nor can a new PIN be set over the one that cannot be read, and then used to turn it off.
            assertEquals(PinResult.AlreadySet, lock.setPin("1234"))
            assertEquals(PinResult.NoPin, lock.disable("1234"))
            assertTrue(lock.now().enabled)
        }

    @Test
    fun theStateAndTheResultsNeverShowThePin() =
        runBlocking {
            val lock = lock()
            lock.setPin("8642")
            lock.enable()
            val shown = listOf(lock.now(), lock.verify("8641"), lock.verify("8642"), store.current.value)
            shown.forEach { assertFalse(it.toString().contains("864"), it.toString()) }
        }

    @Test
    fun theLockoutPolicyStartsALockoutOnlyAtEachFifthFailure() {
        val policy = LockoutPolicy()
        assertNull(policy.lockoutAfter(0))
        assertNull(policy.lockoutAfter(4))
        assertEquals(30.seconds, policy.lockoutAfter(5))
        assertNull(policy.lockoutAfter(6))
        assertEquals(1.minutes, policy.lockoutAfter(10))
        assertEquals(1.hours, policy.lockoutAfter(Int.MAX_VALUE - Int.MAX_VALUE % 5))
        assertNotNull(policy.lockoutAfter(5))
    }
}
