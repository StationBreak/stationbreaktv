// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

/** Crash reports are off until the viewer turns them on (#96), and never count in a build with no endpoint. */
class CrashReportConsentStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PreferenceFiles(folder.root) }
    private val key = booleanPreferencesKey("enabled")
    private val lines = mutableListOf<String>()

    @After fun close() = files.close()

    private fun store(
        data: DataStore<Preferences>,
        available: Boolean = true,
    ) = DataStoreCrashReportConsentStore(data, available, files.writes) { lines += it }

    @Test
    fun aMissingValueIsOff() {
        assertFalse(store(files.data).enabled.value)
    }

    @Test
    fun aChoiceShowsAtOnceAndTheNextReadSeesIt() {
        val first = store(files.data)
        first.choose(true, "settingsRight")
        assertTrue(first.enabled.value)
        files.awaitStored(key, true)
        assertTrue(store(files.reopen()).enabled.value)
        assertEquals(listOf("crash reports choose enabled=true was=false from=settingsRight"), lines)
    }

    @Test
    fun withoutAnEndpointItIsUnavailable_stayedOff_andSavesNothing() {
        runBlocking { files.data.edit { it[key] = true } }
        val none = store(files.reopen(), available = false)
        assertFalse(none.available)
        // An old stored yes does not count where nothing can be sent.
        assertFalse(none.enabled.value)
        none.choose(true, "settingsRight")
        assertFalse(none.enabled.value)
        assertTrue(lines.isEmpty())
    }
}
