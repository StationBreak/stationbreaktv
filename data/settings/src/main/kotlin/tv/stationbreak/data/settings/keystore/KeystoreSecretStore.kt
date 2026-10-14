// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SecretStoreResult
import java.io.File
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SecretStore] that encrypts each value with one AndroidKeyStore AES-256-GCM key and keeps it in its own blob file
 * under `noBackupFilesDir` (ADR-0009, spec 04 Unit 1). It fails closed: every storage fault returns
 * [SecretStoreResult.Failure], never throws and never writes plaintext.
 *
 * A key-level fault (the key is missing, invalidated or isn't the one this store makes) deletes the key and every
 * blob. A blob-level fault on load (a bad tag, a bad length or version) deletes only that blob. Anything else is
 * treated as transient and wipes nothing. The store logs nothing.
 */
class KeystoreSecretStore internal constructor(
    private val keys: KeyAccess,
    private val files: BlobFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {
    constructor(context: Context) : this(AndroidKeyAccess, DiskBlobFiles(File(context.noBackupFilesDir, DIR_NAME)))

    override suspend fun save(
        key: String,
        value: String,
    ): SecretStoreResult<Unit> =
        guarded(blobFile = null) {
            // A missing key on save is a first save: create it. A key that isn't ours is a fault.
            val secretKey = if (keys.get() == null) keys.create() else existingKey()
            files.write(BlobCodec.fileName(key), encrypt(secretKey, key, value))
        }

    override suspend fun load(key: String): SecretStoreResult<String?> {
        val file = BlobCodec.fileName(key)
        return guarded(blobFile = file) {
            // No blob means nothing to decrypt: don't touch, let alone create, the key.
            val blob = files.read(file) ?: return@guarded null
            val decoded = BlobCodec.decode(blob) ?: throw BlobFault()
            decrypt(existingKey(), key, decoded)
        }
    }

    override suspend fun clear(key: String): SecretStoreResult<Unit> {
        val file = BlobCodec.fileName(key)
        return guarded(blobFile = null) { files.delete(file) }
    }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "KeystoreSecretStore"

    /**
     * Runs [block] under the process-wide lock, on a background dispatcher. The whole of a load holds the lock, so the
     * fault it acts on can't change before the wipe: a load can never delete a key or blob a concurrent save just
     * wrote. [blobFile] is the blob a blob-level fault deletes, or null for a call that never decrypts.
     */
    private suspend fun <T> guarded(
        blobFile: String?,
        block: () -> T,
    ): SecretStoreResult<T> =
        withContext(io) {
            LOCK.withLock {
                try {
                    SecretStoreResult.Success(block())
                } catch (e: CancellationException) {
                    // Cancellation says nothing about the key or the blobs.
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Every fault, listed or not, ends in Failure (spec 04): a vendor Keystore throws odd things.
                    when (classify(e, blobFile != null)) {
                        Severity.KEY -> wipeKeyAndBlobs()
                        Severity.BLOB -> blobFile?.let(::wipeBlob)
                        Severity.TRANSIENT -> Unit
                    }
                    SecretStoreResult.Failure
                }
            }
        }

    /** The key under the alias. A missing one, or one with other properties, is a key-level fault. */
    private fun existingKey(): SecretKey {
        val secretKey = keys.get()
        return if (secretKey != null && keys.matchesSpec(secretKey)) secretKey else throw KeyFault()
    }

    private fun classify(
        e: Exception,
        decrypting: Boolean,
    ): Severity =
        when {
            e is KeyFault || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException -> {
                Severity.KEY
            }

            decrypting && (e is BlobFault || e is AEADBadTagException || e is IllegalBlockSizeException) -> {
                Severity.BLOB
            }

            // KeyStoreException, ProviderException, IOException and anything unlisted.
            else -> {
                Severity.TRANSIENT
            }
        }

    // A wipe that itself fails leaves the fault in place: the next call meets it again and retries.
    private fun wipeKeyAndBlobs() {
        runCatching { keys.delete() }
        runCatching { files.deleteAll() }
    }

    private fun wipeBlob(file: String) {
        runCatching { files.delete(file) }
    }

    private fun encrypt(
        secretKey: SecretKey,
        keyName: String,
        value: String,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is passed: the Keystore generates a fresh one for every init.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(BlobCodec.aad(keyName))
        val plain = value.toByteArray(Charsets.UTF_8)
        try {
            return BlobCodec.encode(cipher.iv, cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(
        secretKey: SecretKey,
        keyName: String,
        decoded: BlobCodec.Decoded,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, decoded.iv))
        cipher.updateAAD(BlobCodec.aad(keyName))
        val plain = cipher.doFinal(decoded.ciphertext)
        try {
            return String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    private enum class Severity { KEY, BLOB, TRANSIENT }

    // Internal signals only. They carry no message, so nothing about a value or a name can leak through one.
    private class KeyFault : RuntimeException()

    private class BlobFault : RuntimeException()

    private companion object {
        const val DIR_NAME = "keystore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = BlobCodec.TAG_LENGTH * Byte.SIZE_BITS

        // Process-wide: the alias and the directory are, so two store instances must not interleave either.
        val LOCK = Mutex()
    }
}
