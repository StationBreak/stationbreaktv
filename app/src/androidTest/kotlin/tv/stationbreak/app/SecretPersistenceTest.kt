// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.PersistOutcome
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SecretStoreResult
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredDeviceIdProvider
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.data.settings.keystore.KeystoreSecretStore
import java.util.UUID
import javax.inject.Inject

/**
 * Spec 04, Unit 2: the graph binds the Keystore-backed store, and a session and the device ID that one store saved are
 * read by a store rebuilt from disk, as the next process's store would be. Runs on the API 25 emulator, against the
 * real AndroidKeyStore and the real `noBackupFilesDir`.
 */
@HiltAndroidTest
@OptIn(SecretAccess::class)
class SecretPersistenceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var store: SecretStore

    @Inject lateinit var sameStore: SecretStore

    @Inject lateinit var deviceIds: DeviceIdProvider

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun inject() {
        hilt.inject()
        wipe()
    }

    @After
    fun tidy() {
        wipe()
    }

    private fun wipe() {
        runBlocking {
            store.clear(SessionRepository.SESSION_KEY)
            store.clear(StoredDeviceIdProvider.KEY)
        }
    }

    // What a new process would build: nothing shared with the injected store but the files and the Keystore.
    private fun rebuiltStore(): SecretStore = KeystoreSecretStore(context)

    @Test
    fun theGraphBindsTheKeystoreStore() {
        assertTrue("SecretStore is ${store::class.java.name}", store is KeystoreSecretStore)
    }

    @Test
    fun theStoreIsASingletonAndTheDeviceIdProviderUsesIt() =
        runBlocking {
            assertSame(store, sameStore)
            // Saved through one injection, read through the provider: they share the store.
            store.save(StoredDeviceIdProvider.KEY, SecretFixtures.FAKE_DEVICE_ID)
            assertEquals(SecretFixtures.FAKE_DEVICE_ID, deviceIds.deviceId())
        }

    @Test
    fun aSavedSessionIsRestoredByAStoreRebuiltFromDisk() =
        runBlocking {
            assertEquals(PersistOutcome.Saved, SessionRepository(store, FakeMediaServer()).save(SecretFixtures.session))

            val server = FakeMediaServer().apply { resumeResult = ServerResult.Success(SecretFixtures.session) }
            val outcome = SessionRepository(rebuiltStore(), server).restore(SecretFixtures.address)

            assertEquals(RestoreOutcome.Restored(SecretFixtures.session), outcome)
            val resumed = server.resumeCalls.single()
            assertEquals(SecretFixtures.DUMMY_TOKEN, resumed.token.reveal)
            assertEquals(SecretFixtures.session.userId, resumed.userId)
        }

    @Test
    fun anAbsentSessionIsSignedOutAndAsksNoServer() =
        runBlocking {
            val server = FakeMediaServer()
            val outcome = SessionRepository(rebuiltStore(), server).restore(SecretFixtures.address)
            assertEquals(RestoreOutcome.SignedOut, outcome)
            assertTrue(server.resumeCalls.isEmpty())
        }

    @Test
    fun theDeviceIdIsTheSameThroughTheProviderAfterTheStoreIsRebuilt() =
        runBlocking {
            val first = deviceIds.deviceId()
            UUID.fromString(first)

            val rebuilt = StoredDeviceIdProvider(rebuiltStore()) { "must-not-be-generated" }
            assertEquals(first, rebuilt.deviceId())
            assertEquals(SecretStoreResult.Success(first), rebuiltStore().load(StoredDeviceIdProvider.KEY))
        }

    @Test
    fun signingOutKeepsTheDeviceIdAndDropsTheSessionAcrossARebuild() =
        runBlocking {
            val id = deviceIds.deviceId()
            val repository = SessionRepository(store, FakeMediaServer())
            repository.save(SecretFixtures.session)
            repository.signOut(SecretFixtures.session)

            val fresh = rebuiltStore()
            assertEquals(SecretStoreResult.Success(null), fresh.load(SessionRepository.SESSION_KEY))
            assertEquals(SecretStoreResult.Success(id), fresh.load(StoredDeviceIdProvider.KEY))
            assertNotEquals(SessionRepository.SESSION_KEY, StoredDeviceIdProvider.KEY)
        }
}
