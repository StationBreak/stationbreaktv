// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VolumeLevellingStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PreferenceFiles(folder.root) }
    private val key = booleanPreferencesKey("enabled")
    private val lines = mutableListOf<String>()

    @After fun close() = files.close()

    private fun store(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        DataStoreVolumeLevellingStore(data, files.writes) { lines += it }

    @Test
    fun aMissingValueIsOn() {
        assertTrue(store(files.data).enabled.value)
        assertEquals(listOf("levelling store read enabled=true stored=absent"), lines)
    }

    @Test
    fun aStoredOffIsRead() {
        runBlocking { files.data.edit { it[key] = false } }
        assertFalse(store(files.reopen()).enabled.value)
        assertEquals(listOf("levelling store read enabled=false stored=false"), lines)
    }

    @Test
    fun aChoiceShowsAtOnceAndTheNextReadSeesIt() {
        val first = store(files.data)
        first.choose(false, "settingsLeft")
        assertFalse(first.enabled.value)
        files.awaitStored(key, false)
        assertFalse(store(files.reopen()).enabled.value)
        assertEquals("levelling choose enabled=false was=true from=settingsLeft", lines[1])
    }
}
