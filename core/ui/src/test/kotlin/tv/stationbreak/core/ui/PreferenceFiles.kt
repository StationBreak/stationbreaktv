// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * A real preferences file in [dir], without the app's corruption handler and migration (viewerPreferencesFile), so a
 * test sees the store's own fallbacks. [reopen] closes it and opens it again, so a test reads back what reached the
 * disk, not what a live DataStore holds in memory.
 */
@Suppress("InjectDispatcher") // Real files on the real IO dispatcher, as the app's stores use.
class PreferenceFiles(
    private val dir: File,
) : AutoCloseable {
    private var scope = newScope()
    val writes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var data: DataStore<Preferences> = open()
        private set

    fun reopen(): DataStore<Preferences> {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        scope = newScope()
        data = open()
        return data
    }

    /**
     * Waits until the file holds [expected] under [key]. It reads afresh each time rather than collecting [data]: a
     * collector that starts while a write is in flight is not always sent that write.
     */
    fun <S> awaitStored(
        key: Preferences.Key<S>,
        expected: S,
    ) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (data.data.first()[key] != expected) delay(POLL_MS)
        }
    }

    override fun close() {
        runBlocking {
            writes.coroutineContext.job.cancelAndJoin()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun open() = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
