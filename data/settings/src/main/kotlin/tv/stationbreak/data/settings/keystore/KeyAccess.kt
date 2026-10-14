// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** The AndroidKeyStore, behind a seam so tests can inject faults a real Keystore won't raise on demand. */
internal interface KeyAccess {
    /** The key under [KEY_ALIAS], or null when there is none. Throws what the Keystore throws. */
    fun get(): SecretKey?

    /** Generates the key under [KEY_ALIAS]. The Keystore, not the app, makes every IV. */
    fun create(): SecretKey

    fun delete()

    /** Whether [key] has exactly the properties this store creates. */
    fun matchesSpec(key: SecretKey): Boolean

    companion object {
        const val KEY_ALIAS = "tv.stationbreak.secrets.v1"
    }
}

internal object AndroidKeyAccess : KeyAccess {
    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_SIZE = 256
    private const val PURPOSES = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun get(): SecretKey? = keyStore().getKey(KeyAccess.KEY_ALIAS, null) as? SecretKey

    override fun create(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(KeyAccess.KEY_ALIAS, PURPOSES)
                .setKeySize(KEY_SIZE)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    override fun delete() = keyStore().deleteEntry(KeyAccess.KEY_ALIAS)

    override fun matchesSpec(key: SecretKey): Boolean {
        val info =
            SecretKeyFactory
                .getInstance(key.algorithm, PROVIDER)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return key.algorithm == KeyProperties.KEY_ALGORITHM_AES &&
            info.keySize == KEY_SIZE &&
            // A key someone imported is one whose bytes were outside the Keystore.
            info.origin == KeyProperties.ORIGIN_GENERATED &&
            info.purposes == PURPOSES &&
            info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) &&
            info.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE)) &&
            !info.isUserAuthenticationRequired
    }
}
