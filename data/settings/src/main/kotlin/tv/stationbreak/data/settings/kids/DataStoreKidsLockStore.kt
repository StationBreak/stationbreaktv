// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.kids

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockRecord
import tv.stationbreak.core.domain.kids.KidsLockStore
import tv.stationbreak.core.domain.kids.PinHash
import tv.stationbreak.data.settings.syncDirectoryWithOs
import tv.stationbreak.data.settings.writeStaged
import java.io.File
import java.io.IOException

/**
 * The [KidsLockStore] (#789) in a Jetpack DataStore file of its own, [FILE_NAME] in `noBackupFilesDir`, so it is never
 * backed up or carried to another device, and clearing the app's data clears it. DataStore writes each change to a new
 * file, syncs it and renames it over the old one, and [update] returns only after that. It holds the lock's switch,
 * its cap, the PIN's salt, hash and iterations (never the PIN), the wrong-PIN count and the lockout's times
 * (wall-clock, and the monotonic reading it started at).
 *
 * A file that cannot be read, or that DataStore cannot parse (a damaged file), gives [KidsLockRecord.UNREADABLE]: the
 * lock on with no PIN, logged through [warn], and [update] throws, so no PIN can be set over it. A damaged lock file
 * keeps Kids Mode on, and only clearing the app's data on the TV removes it. So does a missing one: when a PIN is first
 * set an empty [ARMED_NAME] file is written (staged, synced and renamed) before the record, and a record with no PIN
 * beside it reads as [KidsLockRecord.UNREADABLE].  A damaged or missing lock record keeps Kids Mode on.
 * [create] makes one per process: DataStore allows one instance per file.
 */
class DataStoreKidsLockStore internal constructor(
    internal val data: DataStore<Preferences>,
    private val armed: File,
    warn: (String) -> Unit = {},
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
) : KidsLockStore {
    override val record: Flow<KidsLockRecord> =
        data.data.map(::read).catch { e ->
            if (e !is IOException) throw e
            warn("kids lock read failed: ${e.javaClass.simpleName}")
            emit(KidsLockRecord.UNREADABLE)
        }

    override suspend fun update(change: (KidsLockRecord) -> KidsLockRecord): KidsLockRecord {
        var armedHere = false
        try {
            return read(
                data.edit { prefs ->
                    val next = change(decode(prefs))
                    // The marker is on disk before the PIN's record is: a record lost later is then never "no PIN yet".
                    if (next.pin != null && !armed.exists()) {
                        writeStaged(armed, ByteArray(0), syncDirectory)
                        armedHere = true
                    }
                    encode(next, prefs)
                },
            )
        } catch (e: IOException) {
            // The PIN never reached the record, so nothing was ever set: take the marker back, or the lock would be
            // left on over a PIN that does not exist.
            if (armedHere) armed.delete()
            throw e
        }
    }

    override suspend fun forgetPin(): KidsLockRecord {
        val written = data.edit { encode(KidsLockRecord(cap = decode(it).cap), it) }
        // The record first, then the mark: stopped in between, the lock reads as damaged (on, with no PIN), and a
        // second reset finishes it. A mark that cannot be removed is a failure, for the same reason.
        if (armed.exists() && !armed.delete()) throw IOException("kids lock marker could not be removed")
        return read(written)
    }

    // The record [prefs] hold, unless a PIN was once set (the marker is there) and none can be read now: a lost or
    // damaged record keeps the lock on, as a file that cannot be read does.
    private fun read(prefs: Preferences): KidsLockRecord {
        val stored = decode(prefs)
        return if (stored.pin == null && armed.exists()) KidsLockRecord.UNREADABLE else stored
    }

    companion object {
        const val FILE_NAME: String = "kids-lock.preferences_pb"

        /** An empty file written (before the record) when a PIN is first set: "a PIN was once set". */
        const val ARMED_NAME: String = "kids-lock.armed"

        // DataStore allows one instance per file, and Hilt rebuilds its graph per instrumented test inside one process,
        // so the store is kept per file for the life of the process, as the viewer preferences' stores are.
        private val made = HashMap<String, DataStoreKidsLockStore>()

        /** The one store of the app's process over `noBackupFilesDir`, made on the first call. */
        @Synchronized
        fun create(
            context: Context,
            warn: (String) -> Unit,
        ): DataStoreKidsLockStore {
            val dir = context.applicationContext.noBackupFilesDir
            return made.getOrPut(File(dir, FILE_NAME).path) {
                DataStoreKidsLockStore(dataStore(defaultScope()) { File(dir, FILE_NAME) }, File(dir, ARMED_NAME), warn)
            }
        }

        /**
         * The DataStore over [file]. It has no corruption handler: a file it cannot parse is never replaced (that would
         * read as a lock that is off), so the read fails with a `CorruptionException` and [record] gives
         * [KidsLockRecord.UNREADABLE].
         */
        internal fun dataStore(
            scope: CoroutineScope,
            file: () -> File,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = file)

        // DataStore's own default: its file work off the main thread, for the life of the process.
        @Suppress("InjectDispatcher") // A process-wide root, as the viewer preferences' is: nothing to inject into.
        private fun defaultScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private val ENABLED = booleanPreferencesKey("enabled")
        private val CAP = stringPreferencesKey("cap")
        private val PIN_SALT = byteArrayPreferencesKey("pin_salt")
        private val PIN_HASH = byteArrayPreferencesKey("pin_hash")
        private val PIN_ITERATIONS = intPreferencesKey("pin_iterations")
        private val FAILURES = intPreferencesKey("failures")
        private val LOCKED_AT = longPreferencesKey("locked_at_ms")
        private val LOCKED_UNTIL = longPreferencesKey("locked_until_ms")
        private val LOCKED_AT_ELAPSED = longPreferencesKey("locked_at_elapsed_ms")

        /**
         * The record [prefs] hold. An unknown cap reads as the narrowest; a PIN with any part missing reads as none,
         * and the switch is kept as stored, so a damaged PIN never turns the lock off.
         */
        private fun decode(prefs: Preferences): KidsLockRecord {
            val salt = prefs[PIN_SALT]?.takeIf { it.isNotEmpty() }
            val hash = prefs[PIN_HASH]?.takeIf { it.isNotEmpty() }
            val iterations = prefs[PIN_ITERATIONS]?.takeIf { it > 0 }
            val pin = if (salt != null && hash != null && iterations != null) PinHash(salt, hash, iterations) else null
            return KidsLockRecord(
                enabled = prefs[ENABLED] ?: false,
                cap = KidsCap.entries.firstOrNull { it.name == prefs[CAP] } ?: KidsCap.DEFAULT,
                pin = pin,
                failures = (prefs[FAILURES] ?: 0).coerceAtLeast(0),
                lockedAtMs = prefs[LOCKED_AT] ?: 0,
                lockedUntilMs = prefs[LOCKED_UNTIL] ?: 0,
                lockedAtElapsedMs = prefs[LOCKED_AT_ELAPSED] ?: 0,
            )
        }

        private fun encode(
            record: KidsLockRecord,
            prefs: MutablePreferences,
        ) {
            prefs[ENABLED] = record.enabled
            prefs[CAP] = record.cap.name
            val pin = record.pin
            if (pin == null) {
                prefs.remove(PIN_SALT)
                prefs.remove(PIN_HASH)
                prefs.remove(PIN_ITERATIONS)
            } else {
                prefs[PIN_SALT] = pin.salt
                prefs[PIN_HASH] = pin.hash
                prefs[PIN_ITERATIONS] = pin.iterations
            }
            prefs[FAILURES] = record.failures
            prefs[LOCKED_AT] = record.lockedAtMs
            prefs[LOCKED_UNTIL] = record.lockedUntilMs
            prefs[LOCKED_AT_ELAPSED] = record.lockedAtElapsedMs
        }
    }
}
