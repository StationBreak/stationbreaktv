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
 * ADR-0029: version 12 to 13 on the platform SQLite of the API 25 emulator. Room's `MigrationTestHelper` makes the file
 * from the committed `12.json`, runs [Migration12To13] and checks the result against the committed `13.json`. The
 * existing series and version rows survive and the new `series_version.providerIds` column is empty.
 * `Migration12To13Test` runs the rest on the JVM. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13OnDeviceTest {
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
            "INSERT INTO series (serverId, userId, seriesId, libraryId, name, officialRating, genres, tags, " +
                "deletedAtMs, seenFromMs, firstSeenMs, studios, providerIds) VALUES ($keys, 'series-1', '$library', " +
                "'A Show', 'TV-Y7', '', '', NULL, 3000, 1000, '', 'tvdb=1')",
        )
        execSQL(
            "INSERT INTO series_version (serverId, userId, seriesId, seenFromMs, supersededAtMs, libraryId, " +
                "officialRating, genres, tags, deletedAtMs, studios, name) VALUES ($keys, 'series-1', 1000, 3000, " +
                "'$library', 'TV-Y', '', '', NULL, '', 'A Show')",
        )
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> =
        query(sql).use { cursor ->
            val values = ArrayList<String?>()
            while (cursor.moveToNext()) values += if (cursor.isNull(0)) null else cursor.getString(0)
            values
        }

    @Test
    fun theMigratedSchemaMatchesThirteen_theRowsSurvive_andTheVersionHasNoIds() {
        helper.createDatabase(name, 12).apply {
            fill()
            close()
        }

        // runMigrationsAndValidate compares the migrated schema with 13.json, unknown tables and columns included.
        val migrated = helper.runMigrationsAndValidate(name, 13, true, Migration12To13())

        assertEquals(listOf<String?>(""), migrated.strings("SELECT providerIds FROM series_version"))
        assertEquals(listOf<String?>("TV-Y"), migrated.strings("SELECT officialRating FROM series_version"))
        assertEquals(listOf<String?>("tvdb=1"), migrated.strings("SELECT providerIds FROM series"))
        assertEquals(listOf<String?>("$GENERATION"), migrated.strings("SELECT contentGeneration FROM sync_meta"))
        migrated.close()
    }

    private companion object {
        const val GENERATION = 4_242L
    }
}
