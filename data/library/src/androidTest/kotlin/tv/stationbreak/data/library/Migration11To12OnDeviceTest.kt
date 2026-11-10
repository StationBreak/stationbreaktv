// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #607: version 11 to 12 on the platform SQLite of the API 25 emulator. Room's `MigrationTestHelper` makes the file
 * from the committed `11.json`, runs [Migration11To12] and checks the result against the committed `12.json`. The
 * version 11 rows survive and the new `seriesName` column exists and is null. `Migration11To12Test` runs the rest on
 * the JVM. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class Migration11To12OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val keys = "'device-test-server', 'device-test-user'"
    private val library = "3333333333333333333333333333333a"

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun remove() {
        context.deleteDatabase(name)
    }

    private fun SupportSQLiteDatabase.fill() {
        execSQL("INSERT INTO active_account VALUES ($keys)")
        execSQL("INSERT INTO account_state VALUES ($keys, 1, NULL, NULL, 1)")
        execSQL("INSERT INTO sync_meta VALUES ($keys, $GENERATION, 0, 0, 0)")
        execSQL("INSERT INTO library (serverId, userId, libraryId, fetched) VALUES ($keys, '$library', 1)")
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, seriesId, runtimeMs, createdAtMs, " +
                "seenFromMs, firstSeenMs, providerIds) VALUES ($keys, '${"1".padStart(32, '0')}', '$library', " +
                "'Episode', 'Episode One', 'series-1', 1800000, 1600000000000, 2000, 1000, '')",
        )
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> =
        query(sql).use { cursor ->
            val values = ArrayList<String?>()
            while (cursor.moveToNext()) values += if (cursor.isNull(0)) null else cursor.getString(0)
            values
        }

    @Test
    fun theMigratedSchemaMatchesTwelve_theRowSurvives_andTheNewColumnIsNull() {
        helper.createDatabase(name, 11).apply {
            fill()
            close()
        }

        // runMigrationsAndValidate compares the migrated schema with 12.json, unknown tables and columns included.
        val migrated = helper.runMigrationsAndValidate(name, 12, true, Migration11To12())

        assertEquals(listOf<String?>("1"), migrated.strings("SELECT COUNT(*) FROM item"))
        assertEquals(listOf<String?>("Episode One"), migrated.strings("SELECT name FROM item"))
        assertEquals(listOf<String?>("series-1"), migrated.strings("SELECT seriesId FROM item"))
        assertEquals(listOf<String?>(null), migrated.strings("SELECT seriesName FROM item"))
        assertEquals(listOf<String?>("$GENERATION"), migrated.strings("SELECT contentGeneration FROM sync_meta"))
        migrated.close()
    }

    private companion object {
        const val GENERATION = 4_242L
    }
}
