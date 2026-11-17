// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerPreferenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PreferenceFiles(folder.root) }
    private val key = stringPreferencesKey("k")

    @After fun close() = files.close()

    private fun preference() = ViewerPreference(files.data, key, { it ?: "default" }, { it }, files.writes)

    @Test
    fun theLastOfQuickChoicesIsTheOneKept() {
        val preference = preference()
        for (i in 1..CHOICES) preference.choose("v$i")
        files.awaitStored(key, "v$CHOICES")
        files.reopen()
        assertEquals("v$CHOICES", preference().value.value)
    }

    @Test
    fun aCorruptFileReadsAsMissing() {
        files.close()
        folder.root.resolve("test.preferences_pb").writeBytes(byteArrayOf(1, 2, 3))
        val reopened = PreferenceFiles(folder.root)
        try {
            val warnings = mutableListOf<String>()
            val preference =
                ViewerPreference(reopened.data, key, { it ?: "default" }, { it }, reopened.writes) { warnings += it }
            assertEquals("default", preference.value.value)
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().startsWith("preference read failed key=k"))
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val CHOICES = 20
    }
}
