// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.pro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.store.ProCache
import java.io.File
import java.io.IOException

/**
 * The [ProCache] in a Jetpack DataStore file of its own, [FILE_NAME] in `noBackupFilesDir`: never backed up or carried
 * to another TV, and cleared with the app's data. Two booleans: the store's last verified answer, and whether a
 * verified refund still owes its reset; no receipt, user ID or token is ever kept. A file that cannot be read gives
 * false for both (the store's next answer decides), a corrupt one is replaced by an empty one so the next write works
 * (#1063), and a write that fails is dropped: the next verified answer writes again.
 */
class DataStoreProCache internal constructor(
    private val data: DataStore<Preferences>,
) : ProCache {
    override suspend fun read(): Boolean =
        try {
            data.data.first()[OWNED] == true
        } catch (_: IOException) {
            false
        }

    override suspend fun write(owned: Boolean) {
        try {
            data.edit { it[OWNED] = owned }
        } catch (_: IOException) {
            // Only a cache: the store answers again at the next start.
        }
    }

    override suspend fun readRefundPending(): Boolean =
        try {
            data.data.first()[REFUND_PENDING] == true
        } catch (_: IOException) {
            false
        }

    override suspend fun writeRefundPending(pending: Boolean) {
        try {
            data.edit { it[REFUND_PENDING] = pending }
        } catch (_: IOException) {
            // Dropped: the refund still stands in memory for this session.
        }
    }

    companion object {
        const val FILE_NAME: String = "pro-cache.preferences_pb"
        private val OWNED = booleanPreferencesKey("owned")
        private val REFUND_PENDING = booleanPreferencesKey("refund_pending")
        private val made = HashMap<String, DataStoreProCache>()

        /** The one cache of the app's process over `noBackupFilesDir`: DataStore allows one instance per file. */
        @Synchronized
        fun create(context: Context): DataStoreProCache {
            val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
            return made.getOrPut(file.path) { DataStoreProCache(dataStore(defaultScope()) { file }) }
        }

        internal fun dataStore(
            scope: CoroutineScope,
            file: () -> File,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A corrupt file reads as empty: never Pro, nothing owed. Without it every start would fail to read.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = file,
            )

        @Suppress("InjectDispatcher") // A process-wide root, as the other stores': nothing to inject into.
        private fun defaultScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
