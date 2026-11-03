// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Spec 23, D8, D10: version 8 to 9 on the platform SQLite of the API 25 emulator (SQLite 3.9, like Fire OS 6's), where
 * the correlated `UPDATE` of the backfill must run. Room's `MigrationTestHelper` creates the file from the committed
 * `8.json` (and `7.json`), runs [Migration8To9] and checks the result against the committed `9.json`. The migrated file
 * then opens through the same `create` the app uses. `Migration8To9Test` runs the same on the JVM. Invented names only.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val keys = "'device-test-server', 'device-test-user'"
    private val other = "'device-test-server', 'other-test-user'"
    private val library = ItemId("2222222222222222222222222222222a")
    private val now = Instant.fromEpochMilliseconds(1_700_100_000_000)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun remove() {
        context.deleteDatabase(name)
    }

    // One item, one series and one collection under each of two accounts, with the same IDs and different names, so the
    // backfill has to match on the account. [studios] is false for a version 7 file, which has no such columns.
    private fun SupportSQLiteDatabase.fill(studios: Boolean) {
        val lib = "'${library.value}'"
        val studioColumn = if (studios) ", studios" else ""
        val studioValue = if (studios) ", ''" else ""
        for ((who, label) in listOf(keys to "First", other to "Second")) {
            execSQL("INSERT INTO account_state VALUES ($who, 1, NULL, NULL, 1)")
            // Version 8 has backfillPending as a sixth column; the second account's is left pending.
            val generation = if (studios && label == "Second") GENERATION + 100 else GENERATION
            val pending = if (studios) ", ${if (label == "Second") 1 else 0}" else ""
            execSQL("INSERT INTO sync_meta VALUES ($who, $generation, 0, 0$pending)")
            execSQL("INSERT INTO library VALUES ($who, $lib, 1)")
            val item = "'11111111111111111111111111111111'"
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "seenFromMs, firstSeenMs) VALUES ($who, $item, $lib, 'Movie', '$label Film', 5400000, " +
                    "1600000000000, 2000, 1000)",
            )
            execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, genres, tags$studioColumn) VALUES ($who, $item, 1000, 2000, $lib, " +
                    "'Movie', 5400000, 1600000000000, '', ''$studioValue)",
            )
            val seriesStudio = if (studios) ", ''" else ""
            execSQL(
                "INSERT INTO series VALUES ($who, 'show-1', $lib, '$label Show', NULL, '', '', NULL, 2000, 1000" +
                    "$seriesStudio)",
            )
            execSQL(
                "INSERT INTO series_version VALUES ($who, 'show-1', 1000, 2000, $lib, NULL, '', '', NULL" +
                    "$seriesStudio)",
            )
            execSQL("INSERT INTO collection VALUES ($who, 'box-1', '$label Box Set 1 Collection', NULL, 2000, 1000)")
            execSQL("INSERT INTO collection_version VALUES ($who, 'box-1', 1000, 2000, NULL)")
        }
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { cursor ->
            val values = ArrayList<String>()
            while (cursor.moveToNext()) values += cursor.getString(0)
            values
        }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long> =
        query(sql).use { cursor ->
            val values = ArrayList<Long>()
            while (cursor.moveToNext()) values += cursor.getLong(0)
            values
        }

    private fun SupportSQLiteDatabase.assertNames() {
        assertEquals(listOf("First Film", "Second Film"), strings("SELECT name FROM item_version ORDER BY userId"))
        assertEquals(listOf("First Show", "Second Show"), strings("SELECT name FROM series_version ORDER BY userId"))
        val boxes = strings("SELECT name FROM collection_version ORDER BY userId")
        assertEquals(listOf("First Box Set 1 Collection", "Second Box Set 1 Collection"), boxes)
    }

    @Test
    fun versionRowsGainTheCurrentName() {
        helper.createDatabase(name, 8).apply {
            fill(studios = true)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(name, 9, true, Migration8To9 { SEED })

        migrated.assertNames()
        migrated.close()
    }

    @Test
    fun everyAccountsGenerationMoves() {
        helper.createDatabase(name, 8).apply {
            fill(studios = true)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(name, 9, true, Migration8To9 { SEED })

        val generations = migrated.longs("SELECT contentGeneration FROM sync_meta ORDER BY userId")
        assertEquals(listOf(GENERATION + 1, GENERATION + 101), generations)
        // The pending flags are not touched.
        assertEquals(listOf(0L, 1L), migrated.longs("SELECT backfillPending FROM sync_meta ORDER BY userId"))
        migrated.close()
    }

    @Test
    fun aVersionSevenFileMigratesThroughNine() {
        helper.createDatabase(name, 7).apply {
            fill(studios = false)
            close()
        }

        val seed = now.toEpochMilliseconds() shl LibraryDao.GENERATION_SHIFT
        val migrated = helper.runMigrationsAndValidate(name, 9, true, Migration7To8 { seed }, Migration8To9 { seed })

        migrated.assertNames()
        val generations = migrated.longs("SELECT contentGeneration FROM sync_meta ORDER BY userId")
        assertEquals(listOf(GENERATION + 1, GENERATION + 1), generations)
        assertEquals(listOf(1L, 1L), migrated.longs("SELECT backfillPending FROM sync_meta ORDER BY userId"))
        migrated.close()
    }

    @Test
    fun theSchemaMatchesTheExportedNine() {
        helper.createDatabase(name, 8).apply {
            fill(studios = true)
            close()
        }
        // runMigrationsAndValidate compares the migrated schema with 9.json, unknown tables and columns included.
        helper.runMigrationsAndValidate(name, 9, true, Migration8To9 { SEED }).close()

        val repository = RoomLibraryRepository.create(context, clock)
        runBlocking {
            assertEquals("First Film", repository.versions(account).single().name)
            assertEquals(GENERATION + 1, repository.contentGeneration(account))
        }
        repository.close()
    }

    private companion object {
        const val GENERATION = 4_242L
        val SEED = 1L shl LibraryDao.GENERATION_SHIFT
    }
}
