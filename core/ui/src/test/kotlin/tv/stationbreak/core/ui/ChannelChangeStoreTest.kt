// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelChangeStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PreferenceFiles(folder.root) }
    private val key = stringPreferencesKey("change")

    @After fun close() = files.close()

    @Test
    fun aMissingValueIsStan() {
        assertEquals(ChannelChange.Stan, DataStoreChannelChangeStore(files.data, files.writes).change.value)
    }

    @Test
    fun anUnknownStoredValueIsStan() {
        runBlocking { files.data.edit { it[key] = "Static" } }
        assertEquals(ChannelChange.Stan, DataStoreChannelChangeStore(files.reopen(), files.writes).change.value)
    }

    @Test
    fun aStoredSnowIsRead() {
        runBlocking { files.data.edit { it[key] = "Snow" } }
        assertEquals(ChannelChange.Snow, DataStoreChannelChangeStore(files.reopen(), files.writes).change.value)
    }

    @Test
    fun aChoiceShowsAtOnceAndTheNextReadSeesIt() {
        val store = DataStoreChannelChangeStore(files.data, files.writes)
        store.choose(ChannelChange.Snow)
        assertEquals(ChannelChange.Snow, store.change.value)
        files.awaitStored(key, "Snow")
        assertEquals(ChannelChange.Snow, DataStoreChannelChangeStore(files.reopen(), files.writes).change.value)
    }
}
