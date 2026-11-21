// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.kids

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockRecord
import tv.stationbreak.core.domain.kids.PinHash
import tv.stationbreak.core.domain.kids.PinHasher
import tv.stationbreak.core.domain.kids.PinKidsLock
import tv.stationbreak.core.domain.kids.PinResult
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/** The Kids Mode lock's file on a real DataStore in a temporary folder, reopened to read back what reached the disk. */
@Suppress("InjectDispatcher") // Real files on the real IO dispatcher, as the app's store uses.
class DataStoreKidsLockStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val warnings = mutableListOf<String>()

    private val file: File get() = File(folder.root, DataStoreKidsLockStore.FILE_NAME)

    /** A fresh store over the same file, as after a relaunch: the scope of the one before is closed first. */
    private fun open(): DataStoreKidsLockStore {
        close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DataStoreKidsLockStore(DataStoreKidsLockStore.dataStore(scope, { warnings += it }) { file }) {
            warnings += it
        }
    }

    @After
    fun close() =
        runBlocking {
            scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
            scopes.clear()
        }

    private val clock =
        object : Clock {
            var ms = 1_700_000_000_000L

            override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
        }

    private fun lockOver(store: DataStoreKidsLockStore) = PinKidsLock(store, clock, hasher = PinHasher(iterations = 10))

    @Test
    fun aMissingFileIsALockThatIsOffWithNoPin() =
        runBlocking {
            assertEquals(KidsLockRecord(), open().record.first())
        }

    @Test
    fun everyFieldIsReadBackFromANewStore() =
        runBlocking {
            val pin = PinHash(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), 7)
            val saved = KidsLockRecord(true, KidsCap.Family, pin, failures = 6, lockedAtMs = 11, lockedUntilMs = 22)
            open().update { saved }
            assertEquals(saved, open().record.first())
        }

    @Test
    fun theWrongPinCountAndLockoutSurviveARelaunch() =
        runBlocking {
            lockOver(open()).apply {
                assertEquals(PinResult.Ok, setPin("1234"))
                assertTrue(enable())
                repeat(4) { verify("0000") }
            }
            val relaunched = lockOver(open())
            assertEquals(PinResult.LockedOut(30_000), relaunched.disable("0000"))
            clock.ms += 10_000
            assertEquals(PinResult.LockedOut(20_000), lockOver(open()).disable("1234"))
            clock.ms += 20_000
            val last = lockOver(open())
            assertEquals(PinResult.Ok, last.disable("1234"))
            assertFalse(last.state.first().enabled)
        }

    @Test
    fun thePinIsNotInTheFile() =
        runBlocking {
            lockOver(open()).setPin("9731")
            close()
            val bytes = file.readBytes()
            assertTrue(bytes.isNotEmpty())
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("9731"))
        }

    @Test
    fun anUnknownCapReadsAsTheNarrowest() =
        runBlocking {
            val store = open()
            store.update { it.copy(cap = KidsCap.Teen) }
            store.edit { it[stringPreferencesKey("cap")] = "Everything" }
            assertEquals(KidsCap.Kids, open().record.first().cap)
        }

    @Test
    fun anEnabledLockWithAHalfWrittenPinStaysOn() =
        runBlocking {
            val store = open()
            store.edit {
                it[booleanPreferencesKey("enabled")] = true
                it[stringPreferencesKey("cap")] = "Teen"
            }
            val read = open().record.first()
            assertTrue(read.enabled)
            assertEquals(null, read.pin)
        }

    @Test
    fun aCorruptFileIsLoggedAndReadsAsALockThatIsOff() =
        runBlocking {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(KidsLockRecord(), open().record.first())
            assertEquals(1, warnings.size)
            assertNotNull(warnings.single().takeIf { it.startsWith("kids lock file corrupt") })
        }

    /** Edits the raw preferences of [this] store's file, as a damaged or older file would hold them. */
    private suspend fun DataStoreKidsLockStore.edit(change: (MutablePreferences) -> Unit) {
        data.edit(change)
    }
}
