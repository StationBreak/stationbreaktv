// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ADR-0029: version 12 to 13. `series_version` gains `providerIds`, `''` on every existing version row (the ids a
 * version had are not recoverable). A file made from the committed `12.json` is opened through the builder the app
 * uses, and Room checks the result against `13.json`. `Migration12To13OnDeviceTest` runs it on the platform SQLite.
 */
class Migration12To13Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()

    private fun SQLiteConnection.fill() {
        val alice = "'server-1', 'alice'"
        execSQL("INSERT INTO active_account VALUES ($alice)")
        execSQL("INSERT INTO account_state VALUES ($alice, 1, 7, NULL, 1699990000000)")
        execSQL("INSERT INTO library (serverId, userId, libraryId, fetched) VALUES ($alice, '${SHOWS.value}', 1)")
        execSQL("INSERT INTO sync_meta VALUES ($alice, 1234567, 0, 0, 0)")
        execSQL(
            "INSERT INTO series (serverId, userId, seriesId, libraryId, name, officialRating, genres, tags, " +
                "deletedAtMs, seenFromMs, firstSeenMs, studios, providerIds) VALUES ($alice, 'show-1', " +
                "'${SHOWS.value}', 'A Show', 'TV-Y7', '', '', NULL, 3000, 1000, '', 'tvdb=1')",
        )
        execSQL(
            "INSERT INTO series_version (serverId, userId, seriesId, seenFromMs, supersededAtMs, libraryId, " +
                "officialRating, genres, tags, deletedAtMs, studios, name) VALUES ($alice, 'show-1', 1000, 3000, " +
                "'${SHOWS.value}', 'TV-Y', '', '', NULL, '', 'A Show')",
        )
    }

    private fun migrate(file: File) =
        TestLibraries.onDisk(folder.root, name = file.name, clock = clock, signedIn = emptyList()).also {
            runBlocking { it.dao.activeAccounts() }
        }

    private fun <T> raw(
        file: File,
        read: (SQLiteConnection) -> T,
    ): T {
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            return read(connection)
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.dump(sql: String): List<List<String?>> =
        prepare(sql).use { st ->
            buildList {
                while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
            }
        }

    @Test
    fun migration12To13_giveseveryExistingVersionNoIds_andKeepsTheRows() {
        val file = File(folder.root, "twelve.db")
        SchemaFile.create(file, 12) { it.fill() }

        migrate(file).close()

        raw(file) { db ->
            assertEquals(listOf(listOf<String?>("")), db.dump("SELECT providerIds FROM series_version"))
            assertEquals(listOf(listOf<String?>("tvdb=1")), db.dump("SELECT providerIds FROM series"))
            assertEquals(listOf(listOf<String?>("TV-Y")), db.dump("SELECT officialRating FROM series_version"))
            assertEquals(listOf(listOf<String?>("13")), db.dump("PRAGMA user_version"))
        }
    }

    @Test
    fun theSchemaMatchesTheExportedThirteen() {
        val file = File(folder.root, "schema.db")
        SchemaFile.create(file, 12) { it.fill() }
        migrate(file).close()

        val exported = SchemaFile.text()
        assertTrue(exported.contains("\"version\": 13"))
        val hash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(exported)!!.groupValues[1]
        raw(file) { db ->
            assertEquals(listOf(listOf<String?>(hash)), db.dump("SELECT identity_hash FROM room_master_table"))
        }
        assertEquals(
            listOf("ALTER TABLE `series_version` ADD COLUMN `providerIds` TEXT NOT NULL DEFAULT ''"),
            Migration12To13.STATEMENTS,
        )
    }
}
