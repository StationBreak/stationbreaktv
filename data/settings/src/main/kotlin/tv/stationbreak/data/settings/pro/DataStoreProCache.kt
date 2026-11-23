// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.pro

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.store.ProCache
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * The [ProCache] in two Jetpack DataStore files of its own in `noBackupFilesDir`: never backed up or carried to another
 * TV, and cleared with the app's data. [FILE_NAME] holds the store's last verified answer; [REFUND_FILE_NAME] holds
 * whether a verified refund stands and the opaque lineup-owner keys of the accounts already reset for it. No receipt,
 * name or token is ever kept. A cache that cannot be read gives false, and a write that fails is dropped: the next
 * verified answer writes again. A corrupt file is replaced so the next write works (#1063), and each fails closed on
 * its own (#1067): a corrupt cache grants nothing and leaves the refund alone, and a refund file that is corrupt (kept
 * as a mark until the next write) or cannot be read is unknown, with no account handled: no offline Pro and no reset
 * until the store's verified answer settles it. The refund moved out of [FILE_NAME] in #1067; an install that kept it
 * there moves it on first read.
 */
class DataStoreProCache internal constructor(
    private val data: DataStore<Preferences>,
    private val refunds: DataStore<Preferences>,
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

    override suspend fun readVerifiedAt(): Instant? =
        try {
            data.data.first()[VERIFIED_AT]?.let(Instant::fromEpochMilliseconds)
        } catch (_: IOException) {
            null
        } catch (_: ClassCastException) {
            null // A value of the wrong type is a garbled stamp: none.
        } catch (_: IllegalArgumentException) {
            null // A millisecond count out of the range of an Instant.
        }

    override suspend fun writeVerifiedAt(at: Instant) {
        try {
            data.edit { it[VERIFIED_AT] = at.toEpochMilliseconds() }
        } catch (_: IOException) {
            // Only a cache: the next verified answer stamps again.
        }
    }

    override suspend fun readRefundPending(): Boolean? =
        try {
            refunds.data.first().let { if (REFUND_UNKNOWN in it) null else it[REFUND_PENDING] == true }
        } catch (_: IOException) {
            null
        }

    override suspend fun writeRefundPending(pending: Boolean) {
        try {
            refunds.edit {
                it[REFUND_PENDING] = pending
                it.remove(REFUND_UNKNOWN)
                if (!pending) it.remove(REFUND_HANDLED)
            }
        } catch (_: IOException) {
            // Dropped: the refund still stands in memory for this session.
        }
    }

    override suspend fun readRefundHandled(): Set<String> =
        try {
            refunds.data.first()[REFUND_HANDLED].orEmpty()
        } catch (_: IOException) {
            emptySet()
        }

    override suspend fun addRefundHandled(owner: String) {
        try {
            refunds.edit { it[REFUND_HANDLED] = it[REFUND_HANDLED].orEmpty() + owner }
        } catch (_: IOException) {
            // Dropped: that account is asked again at its next sign-in; a second reset finds nothing to reset.
        }
    }

    companion object {
        const val FILE_NAME: String = "pro-cache.preferences_pb"
        const val REFUND_FILE_NAME: String = "pro-refund.preferences_pb"
        private val OWNED = booleanPreferencesKey("owned")
        private val VERIFIED_AT = longPreferencesKey("verified_at_ms")
        private val REFUND_PENDING = booleanPreferencesKey("refund_pending")
        private val REFUND_HANDLED = stringSetPreferencesKey("refund_handled")
        private val REFUND_UNKNOWN = booleanPreferencesKey("refund_unknown")
        private val made = HashMap<String, DataStoreProCache>()

        /** The one cache of the app's process over `noBackupFilesDir`: DataStore allows one instance per file. */
        @Synchronized
        fun create(context: Context): DataStoreProCache {
            val dir = context.applicationContext.noBackupFilesDir
            return made.getOrPut(dir.path) { inDir(defaultScope(), dir) }
        }

        internal fun inDir(
            scope: CoroutineScope,
            dir: File,
        ): DataStoreProCache {
            val data = dataStore(scope) { File(dir, FILE_NAME) }
            val refunds =
                PreferenceDataStoreFactory.create(
                    // Unknown, no account handled: no offline Pro, no reset, until a verified answer writes.
                    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(REFUND_UNKNOWN to true) },
                    migrations = listOf(MoveRefund(data)),
                    scope = scope,
                    produceFile = { File(dir, REFUND_FILE_NAME) },
                )
            return DataStoreProCache(data, refunds)
        }

        internal fun dataStore(
            scope: CoroutineScope,
            file: () -> File,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A corrupt file reads as empty: never Pro. Without it every start would fail to read.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = file,
            )

        /** The #1063 layout kept the refund in [FILE_NAME]: copy it here once, then drop it there. */
        private class MoveRefund(
            private val old: DataStore<Preferences>,
        ) : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                old.data.first().let { REFUND_PENDING in it || REFUND_HANDLED in it }

            override suspend fun migrate(currentData: Preferences): Preferences {
                val kept = old.data.first()
                return currentData.toMutablePreferences().apply {
                    kept[REFUND_PENDING]?.let { this[REFUND_PENDING] = it }
                    kept[REFUND_HANDLED]?.let { this[REFUND_HANDLED] = it }
                }
            }

            override suspend fun cleanUp() {
                old.edit {
                    it.remove(REFUND_PENDING)
                    it.remove(REFUND_HANDLED)
                }
            }
        }

        @Suppress("InjectDispatcher") // A process-wide root, as the other stores': nothing to inject into.
        private fun defaultScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
