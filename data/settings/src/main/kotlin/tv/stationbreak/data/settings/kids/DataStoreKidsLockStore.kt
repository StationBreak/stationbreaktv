// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.kids

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import java.io.File
import java.io.IOException

/**
 * The [KidsLockStore] (#789) in a Jetpack DataStore file of its own, [FILE_NAME] in `noBackupFilesDir`, so it is never
 * backed up or carried to another device, and clearing the app's data clears it. DataStore writes each change to a new
 * file, syncs it and renames it over the old one, and [update] returns only after that. It holds the lock's switch,
 * its cap, the PIN's salt, hash and iterations (never the PIN), the wrong-PIN count and the lockout's times.
 *
 * A file that cannot be read gives [KidsLockRecord.UNREADABLE] (the lock on, failing closed). A corrupt file (one
 * DataStore cannot parse) is logged through [warn] and replaced by an empty one: the lock is off with no PIN, as after
 * clearing the app's data. Make one per process: DataStore allows one instance per file.
 */
class DataStoreKidsLockStore(
    internal val data: DataStore<Preferences>,
    warn: (String) -> Unit = {},
) : KidsLockStore {
    override val record: Flow<KidsLockRecord> =
        data.data.map(::decode).catch { e ->
            if (e !is IOException) throw e
            warn("kids lock read failed: ${e.javaClass.simpleName}")
            emit(KidsLockRecord.UNREADABLE)
        }

    override suspend fun update(change: (KidsLockRecord) -> KidsLockRecord): KidsLockRecord =
        decode(data.edit { prefs -> encode(change(decode(prefs)), prefs) })

    companion object {
        const val FILE_NAME: String = "kids-lock.preferences_pb"

        /** The store of the app's process, in `noBackupFilesDir`. */
        fun create(
            context: Context,
            warn: (String) -> Unit,
        ): DataStoreKidsLockStore {
            val dir = context.applicationContext.noBackupFilesDir
            return DataStoreKidsLockStore(dataStore(defaultScope(), warn) { File(dir, FILE_NAME) }, warn)
        }

        /** The DataStore over [file], whose corrupt file is reported to [warn] and replaced by an empty one. */
        internal fun dataStore(
            scope: CoroutineScope,
            warn: (String) -> Unit,
            file: () -> File,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler =
                    ReplaceFileCorruptionHandler { e ->
                        warn("kids lock file corrupt, reset: ${e.javaClass.simpleName}")
                        emptyPreferences()
                    },
                scope = scope,
                produceFile = file,
            )

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
        }
    }
}
