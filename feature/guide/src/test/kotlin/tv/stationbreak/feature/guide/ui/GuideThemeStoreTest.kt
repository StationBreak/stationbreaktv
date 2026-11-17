// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.ui.ViewerPreference
import java.io.File

@Suppress("InjectDispatcher") // Real files on the real IO dispatcher, as the app's stores use.
class GuideThemeStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = stringPreferencesKey("theme")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun open(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = fileScope) { File(folder.root, "guide-theme.preferences_pb") }

    // Closes the file and opens it again, so the read comes from disk.
    private fun reopen(): DataStore<Preferences> {
        runBlocking { fileScope.coroutineContext.job.cancelAndJoin() }
        fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return open()
    }

    private fun store(data: DataStore<Preferences>) =
        DataStoreGuideThemeStore(
            ViewerPreference(data, DataStoreGuideThemeStore.KEY, GuideTheme::decode, GuideTheme::encode, writes),
        )

    @After
    fun close() =
        runBlocking {
            writes.coroutineContext.job.cancelAndJoin()
            fileScope.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun aMissingValueIsDark() {
        assertEquals(GuideTheme.Dark, store(open()).theme.value)
    }

    @Test
    fun aStoredLightIsRead() {
        runBlocking { open().edit { it[key] = "Light" } }
        assertEquals(GuideTheme.Light, store(reopen()).theme.value)
    }

    @Test
    fun aChoiceShowsAtOnceAndTheNextReadSeesIt() {
        val data = open()
        val store = store(data)
        store.choose(GuideTheme.Light)
        assertEquals(GuideTheme.Light, store.theme.value)
        // Read afresh until the write lands: a collector started mid-write is not always sent that write.
        runBlocking { withTimeout(TIMEOUT_MS) { while (data.data.first()[key] != "Light") delay(POLL_MS) } }
        assertEquals(GuideTheme.Light, store(reopen()).theme.value)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
