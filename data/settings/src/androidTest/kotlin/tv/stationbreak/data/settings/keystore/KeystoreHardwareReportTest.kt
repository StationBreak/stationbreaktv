// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import android.os.Build
import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.SecretStoreResult
import javax.crypto.SecretKeyFactory

/**
 * Spec 04 task 3.4: records, as one logcat line, whether the key the real store creates lives in secure hardware on
 * the device under test (ADR-0009). It asserts nothing about the answer, because an emulator reports false; it asserts
 * only that the key exists. The line carries the answer and the API level, and nothing that identifies the device.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreHardwareReportTest {
    private val store = KeystoreSecretStore(ApplicationProvider.getApplicationContext())

    @After
    fun cleanUp() {
        runBlocking { store.clear("hardware-report") }
        AndroidKeyAccess.delete()
    }

    @Test
    fun reportsWhetherTheKeyIsInsideSecureHardware() {
        val saved = runBlocking { store.save("hardware-report", "dummy-token-0001") }
        assertTrue(saved is SecretStoreResult.Success)

        val key = AndroidKeyAccess.get()
        assertNotNull("the store created no key", key)
        val info =
            SecretKeyFactory
                .getInstance(key!!.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        @Suppress("DEPRECATION")
        val inside = info.isInsideSecureHardware
        Log.i("KeystoreHardware", "insideSecureHardware=$inside api=${Build.VERSION.SDK_INT}")
    }
}
