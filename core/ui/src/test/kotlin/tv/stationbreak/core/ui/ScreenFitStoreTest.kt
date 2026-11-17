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

class ScreenFitStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PreferenceFiles(folder.root) }
    private val key = stringPreferencesKey("fit")

    @After fun close() = files.close()

    @Test
    fun aMissingValueIsSafe() {
        assertEquals(ScreenFit.Safe, DataStoreScreenFitStore(files.data, files.writes).fit.value)
    }

    @Test
    fun aStoredEdgeIsRead() {
        runBlocking { files.data.edit { it[key] = "Edge" } }
        assertEquals(ScreenFit.Edge, DataStoreScreenFitStore(files.reopen(), files.writes).fit.value)
    }

    @Test
    fun aChoiceShowsAtOnceAndTheNextReadSeesIt() {
        val store = DataStoreScreenFitStore(files.data, files.writes)
        store.choose(ScreenFit.Edge)
        assertEquals(ScreenFit.Edge, store.fit.value)
        files.awaitStored(key, "Edge")
        assertEquals(ScreenFit.Edge, DataStoreScreenFitStore(files.reopen(), files.writes).fit.value)
    }
}
