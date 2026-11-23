// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.hours

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.hours.HoursCodec
import tv.stationbreak.core.domain.hours.HoursSchedule
import tv.stationbreak.core.domain.hours.HoursStore
import java.io.File
import java.io.IOException

/**
 * The [HoursStore] (#789, Pro) in a Jetpack DataStore file of its own, [FILE_NAME] in `noBackupFilesDir`: per TV,
 * never backed up, cleared with the app's data. One string, the schedule as [HoursCodec] writes it. A file that cannot
 * be read or parsed (by DataStore or by the codec) reads as no schedule, and is never replaced until the viewer saves
 * one.
 */
class DataStoreHoursStore internal constructor(
    private val data: DataStore<Preferences>,
) : HoursStore {
    override val schedule: Flow<HoursSchedule> =
        data.data
            .map { HoursCodec.decode(it[SCHEDULE]) }
            .catch { e ->
                if (e !is IOException) throw e
                emit(HoursSchedule.EMPTY)
            }

    override suspend fun save(schedule: HoursSchedule) {
        data.edit { it[SCHEDULE] = HoursCodec.encode(schedule) }
    }

    companion object {
        const val FILE_NAME: String = "hours.preferences_pb"
        private val SCHEDULE = stringPreferencesKey("schedule")
        private val made = HashMap<String, DataStoreHoursStore>()

        /** The one store of the app's process over `noBackupFilesDir`: DataStore allows one instance per file. */
        @Synchronized
        fun create(context: Context): DataStoreHoursStore {
            val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
            return made.getOrPut(file.path) { DataStoreHoursStore(dataStore(defaultScope()) { file }) }
        }

        internal fun dataStore(
            scope: CoroutineScope,
            file: () -> File,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A file DataStore cannot parse reads as no schedule, and the viewer's next save replaces it.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = file,
            )

        @Suppress("InjectDispatcher") // A process-wide root, as the other stores': nothing to inject into.
        private fun defaultScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
