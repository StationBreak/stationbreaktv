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
 * #607: the cache's fifth migration, version 11 to 12. `item` gains the nullable `seriesName`, null on every existing
 * row. Nothing else moves: no row is written, `sync_meta` and `item_version` are byte for byte as they were. A file
 * made from the committed `11.json` is opened through the builder the app uses, and Room checks the result against
 * `12.json`. `Migration11To12OnDeviceTest` runs the same on the platform SQLite. Invented names only.
 */
class Migration11To12Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()

    private fun SQLiteConnection.fill() {
        val alice = "'server-1', 'alice'"
        execSQL("INSERT INTO active_account VALUES ($alice)")
        execSQL("INSERT INTO account_state VALUES ($alice, 1, 7, NULL, $LAST_SUCCESS)")
        execSQL("INSERT INTO library (serverId, userId, libraryId, fetched) VALUES ($alice, '${SHOWS.value}', 1)")
        execSQL("INSERT INTO sync_meta VALUES ($alice, $GENERATION, 2, 0, 1)")
        val created = EPOCH.toEpochMilliseconds() - 60_000
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, seriesId, runtimeMs, createdAtMs, " +
                "seenFromMs, firstSeenMs, providerIds) VALUES ($alice, '${hexId(1)}', '${SHOWS.value}', 'Episode', " +
                "'Episode One', 'show-1', 1800000, $created, 2000, 1000, '')",
        )
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, seenFromMs, " +
                "firstSeenMs, providerIds) VALUES ($alice, '${hexId(2)}', '${SHOWS.value}', 'Movie', 'A Film', " +
                "5400000, $created, 2000, 1000, '')",
        )
    }

    private fun migrate(file: File): RoomLibraryRepository =
        TestLibraries.onDisk(folder.root, name = file.name, clock = clock, signedIn = emptyList()).also {
            runBlocking { it.dao.activeAccounts() } // Room opens the file, and so migrates and checks it.
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

    private val oldItemColumns =
        "SELECT serverId, userId, itemId, libraryId, kind, name, parentId, seriesId, seasonId, seasonNumber, " +
            "episodeNumber, runtimeMs, createdAtMs, premiereDate, productionYear, officialRating, deletedAtMs, " +
            "seenFromMs, firstSeenMs, providerIds FROM item ORDER BY rowid"

    @Test
    fun migration11To12_addsANullSeriesName_andWritesNoRow() =
        runBlocking {
            val file = File(folder.root, "eleven.db")
            SchemaFile.create(file, 11) { it.fill() }
            val itemsBefore = raw(file) { it.dump(oldItemColumns) }
            val metaBefore = raw(file) { it.dump("SELECT * FROM sync_meta ORDER BY rowid") }
            val versionsBefore = raw(file) { it.dump("SELECT * FROM item_version") }

            migrate(file).close()

            raw(file) { db ->
                assertEquals(
                    "every existing item has no series name yet",
                    listOf(listOf<String?>(null), listOf(null)),
                    db.dump("SELECT seriesName FROM item ORDER BY rowid"),
                )
                assertEquals("no item column moves", itemsBefore, db.dump(oldItemColumns))
                assertEquals("sync_meta is as it was", metaBefore, db.dump("SELECT * FROM sync_meta ORDER BY rowid"))
                assertEquals("no version is made", versionsBefore, db.dump("SELECT * FROM item_version"))
                assertEquals(listOf(listOf<String?>("12")), db.dump("PRAGMA user_version"))
            }
        }

    @Test
    fun theSchemaMatchesTheExportedTwelve() =
        runBlocking {
            val file = File(folder.root, "schema.db")
            SchemaFile.create(file, 11) { it.fill() }
            migrate(file).close()

            val exported = SchemaFile.text()
            assertTrue(exported.contains("\"version\": 12"))
            val hash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(exported)!!.groupValues[1]
            raw(file) { db ->
                assertEquals(listOf(listOf<String?>(hash)), db.dump("SELECT identity_hash FROM room_master_table"))
            }
        }

    @Test
    fun theMigrationColumnIsTheExportedColumn() {
        val exported = SchemaFile.text()
        assertTrue(exported.contains("`seriesName` TEXT"))
        assertEquals(listOf("ALTER TABLE `item` ADD COLUMN `seriesName` TEXT"), Migration11To12.STATEMENTS)
    }

    @Test
    fun aVersionTenFileMigratesThroughTwelve() =
        runBlocking {
            val file = File(folder.root, "ten.db")
            SchemaFile.create(file, 10) {
                it.execSQL("INSERT INTO active_account VALUES ('server-1', 'alice')")
                it.execSQL("INSERT INTO account_state VALUES ('server-1', 'alice', 1, 7, NULL, $LAST_SUCCESS)")
            }

            migrate(file).close()

            raw(file) { db -> assertEquals(listOf(listOf<String?>("12")), db.dump("PRAGMA user_version")) }
        }

    private companion object {
        const val GENERATION = 1_234_567L
        const val LAST_SUCCESS = 1_699_990_000_000L
    }
}
