// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val OLD_FILE = "viewer-preference-migration-test"
private val Context.migratedFile by viewerPreferencesFile(OLD_FILE)

/**
 * A TV updated from a version that kept the viewer's choices in SharedPreferences keeps them (#820): the old file's
 * values are copied into the DataStore file of the same name once, and the old file is deleted. A regression here would
 * reset every viewer's choices, with no way back.
 */
@RunWith(AndroidJUnit4::class)
class ViewerPreferenceMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Suppress("ForbiddenMethodCall") // Writes the old SharedPreferences file the migration reads.
    fun anOldSharedPreferencesFileIsCopiedInOnceAndDeleted() {
        val old = context.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
        assertTrue(
            old
                .edit()
                .putString("fit", "Edge")
                .putBoolean("enabled", false)
                .commit(),
        )
        val xml = File(context.filesDir.parentFile, "shared_prefs/$OLD_FILE.xml")
        assertTrue(xml.exists())

        val read = runBlocking { context.migratedFile.data.first() }

        assertEquals("Edge", read[stringPreferencesKey("fit")])
        assertEquals(false, read[booleanPreferencesKey("enabled")])
        assertFalse(xml.exists())
    }
}
