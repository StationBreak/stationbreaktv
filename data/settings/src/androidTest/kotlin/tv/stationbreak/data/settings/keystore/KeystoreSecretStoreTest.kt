// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.SecretStoreResult
import java.io.File
import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Spec 04 Unit 1 on the API 25 emulator: the real AndroidKeyStore, with faults injected only through the seams. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private lateinit var dir: File
    private lateinit var disk: DiskBlobFiles
    private val keys = FaultyKeys(AndroidKeyAccess)
    private lateinit var faultyFiles: FaultyFiles
    private val parallel = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

    private fun newStore() = KeystoreSecretStore(keys, faultyFiles)

    private val token = "dummy-token-0001"
    private val deviceId = "00000000-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dir = File(context.noBackupFilesDir, "keystore-test-${UUID.randomUUID()}")
        disk = DiskBlobFiles(dir)
        faultyFiles = FaultyFiles(disk)
        AndroidKeyAccess.delete()
    }

    @After
    fun tearDown() {
        AndroidKeyAccess.delete()
        dir.deleteRecursively()
    }

    @After
    fun closeParallel() = parallel.close()

    private fun blobNames(): List<String> = dir.list().orEmpty().sorted()

    private fun keyExists() = AndroidKeyAccess.get() != null

    private fun assertFailure(result: SecretStoreResult<*>) = assertEquals(SecretStoreResult.Failure, result)

    private fun blobOf(name: String) = disk.read(BlobCodec.fileName(name))!!

    private fun putBlob(
        name: String,
        bytes: ByteArray,
    ) = disk.write(BlobCodec.fileName(name), bytes)

    private fun store2Entries(store: KeystoreSecretStore) =
        runBlocking {
            store.save("token", token)
            store.save("device", deviceId)
        }

    // 1.4 Round trip

    @Test
    fun saveLoadAndClearRoundTrip() =
        runBlocking {
            val store = newStore()

            assertEquals(SecretStoreResult.Success(Unit), store.save("token", token))
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
            assertEquals(SecretStoreResult.Success(Unit), store.clear("token"))
            assertEquals(SecretStoreResult.Success(null), store.load("token"))
            assertTrue(blobNames().isEmpty())
        }

    @Test
    fun aNewStoreInstanceReadsWhatTheOldOneSaved() =
        runBlocking {
            newStore().save("token", token)

            assertEquals(SecretStoreResult.Success(token), newStore().load("token"))
        }

    @Test
    fun loadOfAnAbsentNameReturnsNullAndCreatesNoKey() =
        runBlocking {
            assertEquals(SecretStoreResult.Success(null), newStore().load("token"))

            assertFalse(keyExists())
            assertEquals(0, keys.createCount.get())
        }

    @Test
    fun clearOfAnAbsentNameSucceeds() =
        runBlocking {
            assertEquals(SecretStoreResult.Success(Unit), newStore().clear("token"))
        }

    @Test
    fun twoSavesOfOneValueGiveDifferentBlobs() =
        runBlocking {
            val store = newStore()
            store.save("token", token)
            val first = blobOf("token")
            store.save("token", token)
            val second = blobOf("token")

            assertFalse(first.contentEquals(second))
            assertEquals(first.size, second.size)
        }

    @Test
    fun anEmptyValueAndANonAsciiValueRoundTrip() =
        runBlocking {
            val store = newStore()
            store.save("empty", "")
            store.save("unicode", "dummy-é-☃")

            assertEquals(SecretStoreResult.Success(""), store.load("empty"))
            assertEquals(SecretStoreResult.Success("dummy-é-☃"), store.load("unicode"))
        }

    @Test
    fun theBlobFileNameAndContentsHoldNeitherTheNameNorTheValue() =
        runBlocking {
            newStore().save("token", token)

            assertEquals(listOf(BlobCodec.fileName("token")), blobNames())
            assertFalse(String(blobOf("token"), Charsets.ISO_8859_1).contains(token))
        }

    // 1.5 Key creation

    @Test
    fun theCreatedKeyHasTheSpecProperties() =
        runBlocking {
            newStore().save("token", token)

            assertTrue(AndroidKeyAccess.matchesSpec(AndroidKeyAccess.get()!!))
            assertEquals(1, keys.createCount.get())
        }

    @Test
    fun aMismatchedKeyUnderTheAliasIsKeyLevelAndWipesEverything() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            plantMismatchedKey()

            assertFailure(store.load("token"))

            assertFalse(keyExists())
            assertTrue(blobNames().isEmpty())
        }

    @Test
    fun aMismatchedKeyIsAlsoKeyLevelOnSaveAndTheNextSaveSucceeds() =
        runBlocking {
            val store = newStore()
            plantMismatchedKey()

            assertFailure(store.save("token", token))
            assertFalse(keyExists())

            assertEquals(SecretStoreResult.Success(Unit), store.save("token", token))
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
        }

    @Test
    fun anImportedKeyUnderTheAliasIsKeyLevelAndWipesEverything() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            plantImportedKey()

            assertFailure(store.load("token"))

            assertFalse(keyExists())
            assertTrue(blobNames().isEmpty())
        }

    private fun plantImportedKey() {
        AndroidKeyAccess.delete()
        val protection =
            KeyProtection
                .Builder(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        val entry = KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(32) { 3 }, KeyProperties.KEY_ALGORITHM_AES))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.setEntry(KeyAccess.KEY_ALIAS, entry, protection)
    }

    private fun plantMismatchedKey() {
        AndroidKeyAccess.delete()
        val spec =
            KeyGenParameterSpec
                .Builder(KeyAccess.KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(128)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    // 1.6 Mutex

    @Test
    fun concurrentFirstSavesCreateExactlyOneKey() =
        runBlocking {
            keys.createDelayMs = 50
            val store = newStore()

            (1..8)
                .map { i -> async(parallel) { store.save("name$i", "dummy-$i") } }
                .awaitAll()

            assertEquals(1, keys.createCount.get())
            for (i in 1..8) assertEquals(SecretStoreResult.Success("dummy-$i"), store.load("name$i"))
        }

    @Test
    fun aLoadRacingAFirstSaveNeverWipesWhatTheSaveWrote() =
        runBlocking {
            // A stale blob with a valid header and no key: the load takes the key-level wipe path.
            putBlob("token", BlobCodec.encode(ByteArray(BlobCodec.IV_LENGTH), ByteArray(32)))
            val gate = Gate()
            keys.getGate = gate
            val store = newStore()

            // The load has seen "no key" and stops there, holding the lock.
            val load = async(parallel) { store.load("token") }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            val save = async(parallel) { store.save("token", token) }

            // Without the lock the save would finish now, and the load would then wipe what it wrote.
            withTimeoutOrNull(500) { save.await() }
            gate.release.countDown()

            assertEquals(SecretStoreResult.Failure, load.await())
            assertEquals(SecretStoreResult.Success(Unit), save.await())
            assertTrue(keyExists())
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
        }

    // 1.7 Blob-level faults

    private fun assertBlobFaultWipesOnlyThatBlob(damage: () -> Unit) =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            damage()

            assertFailure(store.load("token"))

            assertEquals(listOf(BlobCodec.fileName("device")), blobNames())
            assertTrue(keyExists())
            assertEquals(SecretStoreResult.Success(deviceId), store.load("device"))
        }

    @Test
    fun aFlippedByteIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            val blob = blobOf("token")
            blob[blob.size - 20] = (blob[blob.size - 20].toInt() xor 1).toByte()
            putBlob("token", blob)
        }

    @Test
    fun aFlippedTagByteIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            val blob = blobOf("token")
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
            putBlob("token", blob)
        }

    @Test
    fun aTruncatedBlobIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            val blob = blobOf("token")
            putBlob("token", blob.copyOf(blob.size - 3))
        }

    @Test
    fun aBlobShorterThanTheTagIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            putBlob("token", blobOf("token").copyOf(2 + 12 + 5))
        }

    @Test
    fun aBlobTruncatedInsideTheIvIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            putBlob("token", blobOf("token").copyOf(8))
        }

    @Test
    fun aBadVersionIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            val blob = blobOf("token")
            blob[0] = 2
            putBlob("token", blob)
        }

    @Test
    fun aBadIvLengthIsBlobLevel() =
        assertBlobFaultWipesOnlyThatBlob {
            val blob = blobOf("token")
            blob[1] = 16
            putBlob("token", blob)
        }

    @Test
    fun anEmptyBlobFileIsBlobLevel() = assertBlobFaultWipesOnlyThatBlob { putBlob("token", ByteArray(0)) }

    @Test
    fun aBlobCopiedToAnotherKeyNameFailsBecauseTheNameIsAad() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            putBlob("device", blobOf("token"))

            assertFailure(store.load("device"))

            assertEquals(listOf(BlobCodec.fileName("token")), blobNames())
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
            assertTrue(keyExists())
        }

    // 1.8 Key-level faults

    private fun assertKeyFaultWipesEverythingThenRecovers(
        fault: (KeystoreSecretStore) -> Unit,
        trigger: suspend (KeystoreSecretStore) -> SecretStoreResult<*>,
    ) = runBlocking {
        val store = newStore()
        store2Entries(store)
        fault(store)

        assertFailure(trigger(store))

        keys.getFault = null
        assertFalse(keyExists())
        assertTrue(blobNames().isEmpty())
        assertEquals(SecretStoreResult.Success(null), store.load("token"))
        assertEquals(SecretStoreResult.Success(Unit), store.save("token", token))
        assertEquals(SecretStoreResult.Success(token), store.load("token"))
        assertTrue(keyExists())
    }

    @Test
    fun aMissingKeyWithBlobsKeptIsKeyLevelOnLoad() =
        assertKeyFaultWipesEverythingThenRecovers({ AndroidKeyAccess.delete() }, { it.load("token") })

    @Test
    fun aMissingKeyOnSaveWipesTheStaleBlobsBeforeCreatingTheNewKey() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            AndroidKeyAccess.delete()

            assertEquals(SecretStoreResult.Success(Unit), store.save("other", "dummy-2"))

            assertEquals(SecretStoreResult.Success("dummy-2"), store.load("other"))
            assertEquals(SecretStoreResult.Success(null), store.load("token"))
            assertEquals(listOf(BlobCodec.fileName("other")), blobNames())
        }

    @Test
    fun aFaultWipingStaleBlobsOnAFirstSaveIsATransientFailure() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            AndroidKeyAccess.delete()
            faultyFiles.deleteFault = IOException("dummy")

            assertFailure(store.save("other", "dummy-2"))

            faultyFiles.deleteFault = null
            assertFalse(keyExists())
            assertEquals(2, blobNames().size)
        }

    @Test
    fun aPermanentlyInvalidatedKeyIsKeyLevelOnLoad() =
        assertKeyFaultWipesEverythingThenRecovers(
            { keys.getFault = KeyPermanentlyInvalidatedException() },
            { it.load("token") },
        )

    @Test
    fun aPermanentlyInvalidatedKeyIsKeyLevelOnSave() =
        assertKeyFaultWipesEverythingThenRecovers(
            { keys.getFault = KeyPermanentlyInvalidatedException() },
            { it.save("token", "dummy-2") },
        )

    @Test
    fun anUnrecoverableKeyIsKeyLevelOnLoad() =
        assertKeyFaultWipesEverythingThenRecovers({ keys.getFault = UnrecoverableKeyException() }, { it.load("token") })

    @Test
    fun anUnrecoverableKeyIsKeyLevelOnSave() =
        assertKeyFaultWipesEverythingThenRecovers(
            { keys.getFault = UnrecoverableKeyException() },
            { it.save("token", "dummy-2") },
        )

    // 1.9 Transient and unlisted faults

    private fun assertTransient(
        arm: () -> Unit,
        disarm: () -> Unit,
        trigger: suspend (KeystoreSecretStore) -> SecretStoreResult<*>,
    ) = runBlocking {
        val store = newStore()
        store2Entries(store)
        val blobsBefore = blobNames()
        val tokenBlob = blobOf("token")
        arm()

        assertFailure(trigger(store))

        disarm()
        assertTrue(keyExists())
        assertEquals(blobsBefore, blobNames())
        assertArrayEquals(tokenBlob, blobOf("token"))
        assertEquals(SecretStoreResult.Success(token), store.load("token"))
    }

    private val transientFaults: List<() -> Throwable> =
        listOf(
            { KeyStoreException("dummy") },
            { ProviderException("dummy") },
            { IOException("dummy") },
            { InvalidAlgorithmParameterException("dummy") },
            { java.security.InvalidKeyException("dummy") },
            { IllegalStateException("dummy") },
            { RuntimeException("dummy") },
        )

    @Test
    fun transientAndUnlistedFaultsFromTheKeystoreOnLoadKeepEverything() {
        for (fault in transientFaults) {
            tearDown()
            setUp()
            assertTransient({ keys.getFault = fault() }, { keys.getFault = null }) { it.load("token") }
        }
    }

    @Test
    fun transientAndUnlistedFaultsFromTheKeystoreOnSaveKeepEverything() {
        for (fault in transientFaults) {
            tearDown()
            setUp()
            assertTransient({ keys.getFault = fault() }, { keys.getFault = null }) { it.save("token", "dummy-2") }
        }
    }

    @Test
    fun aFaultCreatingTheKeyKeepsTheBlobsAndThrowsNothing() =
        runBlocking {
            keys.createFault = ProviderException("dummy")
            val store = newStore()

            assertFailure(store.save("token", token))

            keys.createFault = null
            assertTrue(blobNames().isEmpty())
            assertEquals(SecretStoreResult.Success(Unit), store.save("token", token))
        }

    @Test
    fun anIoFaultReadingABlobKeepsTheKeyAndEveryBlob() =
        assertTransient(
            { faultyFiles.readFault = IOException("dummy") },
            { faultyFiles.readFault = null },
        ) { it.load("token") }

    @Test
    fun anIoFaultWritingABlobKeepsTheKeyAndTheOldBlob() =
        assertTransient(
            { faultyFiles.writeFault = IOException("dummy") },
            { faultyFiles.writeFault = null },
        ) { it.save("token", "dummy-2") }

    @Test
    fun anIoFaultDeletingABlobIsAFailureNotAThrow() =
        runBlocking {
            val store = newStore()
            store.save("token", token)
            faultyFiles.deleteFault = IOException("dummy")

            assertFailure(store.clear("token"))

            faultyFiles.deleteFault = null
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
        }

    // 1.10 Cancellation

    @Test
    fun cancellationDuringALoadPropagatesAndWipesNothing() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            val blobs = blobNames()
            keys.getFault = CancellationException("dummy")

            try {
                store.load("token")
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }

            keys.getFault = null
            assertTrue(keyExists())
            assertEquals(blobs, blobNames())
        }

    @Test
    fun cancellationDuringASavePropagatesAndWipesNothing() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            val blobs = blobNames()
            keys.getFault = CancellationException("dummy")

            try {
                store.save("token", "dummy-2")
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }

            keys.getFault = null
            assertTrue(keyExists())
            assertEquals(blobs, blobNames())
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
        }

    @Test
    fun cancellationWhileWaitingForTheLockWipesNothing() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            val blobs = blobNames()
            val gate = Gate()
            keys.getGate = gate

            val holder = async(parallel) { store.load("token") }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            val waiter = async(parallel) { store.load("device") }
            withTimeoutOrNull(200) { waiter.await() }
            waiter.cancel()
            gate.release.countDown()

            assertEquals(SecretStoreResult.Success(token), holder.await())
            try {
                waiter.await()
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }
            assertTrue(keyExists())
            assertEquals(blobs, blobNames())
        }

    @Test
    fun cancellationInTheMiddleOfACallWipesNothing() =
        runBlocking {
            val store = newStore()
            store2Entries(store)
            val blobs = blobNames()
            val gate = Gate()
            keys.getGate = gate

            val call = async(parallel) { store.load("token") }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            call.cancel()
            gate.release.countDown()

            try {
                call.await()
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }
            assertTrue(keyExists())
            assertEquals(blobs, blobNames())
            assertEquals(SecretStoreResult.Success(token), store.load("token"))
        }

    // 1.11 No secret in toString

    @Test
    fun noToStringHoldsAValue() =
        runBlocking {
            val store = newStore()
            val results =
                listOf(
                    store.save("token", token),
                    store.clear("nothing"),
                    store.load("nothing"),
                )
            plantMismatchedKey()
            val failure = store.load("token")

            for (text in results.map { it.toString() } + failure.toString() + store.toString()) {
                assertFalse(text, text.contains(token))
                assertFalse(text, text.contains("token"))
            }
        }

    // Seams

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FaultyKeys(
        private val delegate: KeyAccess,
    ) : KeyAccess {
        @Volatile var getFault: Throwable? = null

        @Volatile var createFault: Throwable? = null

        @Volatile var createDelayMs = 0L
        val createCount = AtomicInteger()

        @Volatile var getGate: Gate? = null
        private val gateTaken = AtomicBoolean(false)

        override fun get(): SecretKey? {
            getFault?.let { throw it }
            val key = delegate.get()
            // Only the first caller stops, after it has read the key: the rest of its call acts on a stale view.
            val gate = getGate
            if (gate != null && gateTaken.compareAndSet(false, true)) {
                gate.entered.countDown()
                gate.release.await(10, TimeUnit.SECONDS)
            }
            return key
        }

        override fun create(): SecretKey {
            createFault?.let { throw it }
            createCount.incrementAndGet()
            if (createDelayMs > 0) Thread.sleep(createDelayMs)
            return delegate.create()
        }

        override fun delete() = delegate.delete()

        override fun matchesSpec(key: SecretKey) = delegate.matchesSpec(key)
    }

    private class FaultyFiles(
        private val delegate: BlobFiles,
    ) : BlobFiles {
        @Volatile var readFault: Throwable? = null

        @Volatile var writeFault: Throwable? = null

        @Volatile var deleteFault: Throwable? = null

        override fun read(name: String): ByteArray? {
            readFault?.let { throw it }
            return delegate.read(name)
        }

        override fun write(
            name: String,
            bytes: ByteArray,
        ) {
            writeFault?.let { throw it }
            delegate.write(name, bytes)
        }

        override fun delete(name: String) {
            deleteFault?.let { throw it }
            delegate.delete(name)
        }

        override fun deleteAll() {
            deleteFault?.let { throw it }
            delegate.deleteAll()
        }
    }
}
