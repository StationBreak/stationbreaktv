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
    private val key = stringPreferencesKey("style")
    private val lines = mutableListOf<String>()

    @After fun close() = files.close()

    private fun store(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        DataStoreChannelChangeStore(data, files.writes) { lines += it }

    @Test
    fun aMissingValueIsStan() {
        assertEquals(ChannelChangeStyle.Stan, store(files.data).style.value)
        assertEquals(listOf("channel change store read style=Stan stored=absent"), lines)
    }

    @Test
    fun anUnknownStoredValueIsStan() {
        runBlocking { files.data.edit { it[key] = "static" } }
        assertEquals(ChannelChangeStyle.Stan, store(files.reopen()).style.value)
    }

    @Test
    fun aStoredSnowIsRead() {
        runBlocking { files.data.edit { it[key] = "snow" } }
        assertEquals(ChannelChangeStyle.Snow, store(files.reopen()).style.value)
        assertEquals(listOf("channel change store read style=Snow stored=snow"), lines)
    }

    @Test
    fun theStoredWordsAreFixedLiteralsNotConstantNames() {
        assertEquals("stan", ChannelChangeStyle.encode(ChannelChangeStyle.Stan))
        assertEquals("snow", ChannelChangeStyle.encode(ChannelChangeStyle.Snow))
    }

    @Test
    fun aChoiceShowsAtOnceIsLoggedAndTheNextReadSeesIt() {
        val first = store(files.data)
        first.choose(ChannelChangeStyle.Snow, "settingsRight")
        assertEquals(ChannelChangeStyle.Snow, first.style.value)
        files.awaitStored(key, "snow")
        assertEquals(ChannelChangeStyle.Snow, store(files.reopen()).style.value)
        assertEquals("channel change choose style=Snow was=Stan from=settingsRight", lines[1])
    }
}
