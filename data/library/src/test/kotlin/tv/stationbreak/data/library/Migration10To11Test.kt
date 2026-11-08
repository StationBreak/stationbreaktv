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
 * Spec 26, Part 4: the cache's fourth migration, version 10 to 11. `library` gains `walkedStamp` (null),
 * `rewalkPending` (0) and `coveredFromMs` (null); the `item_also_listed` table is created empty, with its library
 * index; `item` gains its series index. Nothing else moves: no row is written, `sync_meta` and `account_state` are
 * byte for byte as they were. A file made from the committed `10.json` is opened through the builder the app uses, and
 * Room checks the result against `11.json`. `Migration10To11OnDeviceTest` runs the same on the platform SQLite.
 * Invented names only.
 */
class Migration10To11Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()

    // Alice has a chosen library, a held one and one being added, with a series' episode in the chosen one; Bob has a
    // chosen library and no sync_meta row (the migration must not make one: 11 writes no row).
    private fun SQLiteConnection.fill() {
        val alice = "'server-1', 'alice'"
        val bob = "'server-1', 'bob'"
        for (account in listOf(alice, bob)) {
            execSQL("INSERT INTO active_account VALUES ($account)")
            execSQL("INSERT INTO account_state VALUES ($account, 1, 7, NULL, $LAST_SUCCESS)")
            execSQL("INSERT INTO library VALUES ($account, '${MOVIES.value}', 1, NULL, NULL)")
        }
        execSQL("INSERT INTO sync_meta VALUES ($alice, $GENERATION, 2, 0, 1)")
        execSQL("INSERT INTO library VALUES ($alice, '${SHOWS.value}', 1, $HELD_UNTIL, NULL)")
        execSQL("INSERT INTO library VALUES ($alice, '$ADDING', 0, NULL, $STAMP)")
        val created = EPOCH.toEpochMilliseconds() - 60_000
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, seriesId, runtimeMs, createdAtMs, " +
                "seenFromMs, firstSeenMs, providerIds) VALUES ($alice, '${hexId(1)}', '${MOVIES.value}', 'Episode', " +
                "'Episode One', 'show-1', 1800000, $created, 2000, 1000, '')",
        )
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, seenFromMs, " +
                "firstSeenMs, providerIds) VALUES ($bob, '${hexId(2)}', '${MOVIES.value}', 'Movie', 'Bob Film', " +
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

    // Every row of [table] as text, column by column, in rowid order: what "byte for byte the same" compares.
    private fun SQLiteConnection.dump(table: String): List<List<String?>> =
        prepare("SELECT * FROM `$table` ORDER BY rowid").use { st ->
            buildList {
                while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
            }
        }

    private fun SQLiteConnection.strings(sql: String): List<String> =
        prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    @Test
    fun migration10To11_addsNullAndZeroColumns_emptyAlsoListed_bothIndexes_writesNoRow() =
        runBlocking {
            val file = File(folder.root, "ten.db")
            SchemaFile.create(file, 10) { it.fill() }
            val metaBefore = raw(file) { it.dump("sync_meta") }
            val stateBefore = raw(file) { it.dump("account_state") }
            val itemsBefore = raw(file) { it.dump("item") }

            migrate(file).close()

            raw(file) { db ->
                val columns = "walkedStamp, rewalkPending, coveredFromMs"
                val libraries =
                    db.prepare("SELECT $columns FROM library ORDER BY rowid").use { st ->
                        buildList {
                            while (st.step()) {
                                add(Triple(if (st.isNull(0)) null else st.getLong(0), st.getLong(1), st.isNull(2)))
                            }
                        }
                    }
                assertEquals(4, libraries.size)
                assertTrue("every row starts with no proof, no mark and no coverage: $libraries") {
                    libraries.all { it == Triple(null, 0L, true) }
                }
                assertEquals(0, db.dump("item_also_listed").size)
                val alsoIndexes = db.strings("SELECT name FROM pragma_index_list('item_also_listed')")
                assertTrue(alsoIndexes.toString(), "index_item_also_listed_serverId_userId_libraryId" in alsoIndexes)
                val itemIndexes = db.strings("SELECT name FROM pragma_index_list('item')")
                assertTrue(itemIndexes.toString(), "index_item_serverId_userId_seriesId" in itemIndexes)
                assertEquals("sync_meta is as it was (backfillPending, generation)", metaBefore, db.dump("sync_meta"))
                assertEquals("account_state is as it was", stateBefore, db.dump("account_state"))
                assertEquals("no item row moves", itemsBefore, db.dump("item"))
                assertEquals(listOf("11"), db.strings("PRAGMA user_version"))
            }
        }

    @Test
    fun migratedAlsoListedRowsCascadeWithTheirLibrary() =
        runBlocking {
            val file = File(folder.root, "cascade.db")
            SchemaFile.create(file, 10) { it.fill() }
            migrate(file).close()

            raw(file) { db ->
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL(
                    "INSERT INTO item_also_listed VALUES ('server-1', 'alice', '${hexId(1)}', '${SHOWS.value}')",
                )
                db.execSQL(
                    "DELETE FROM library WHERE serverId = 'server-1' AND userId = 'alice' " +
                        "AND libraryId = '${SHOWS.value}'",
                )
                assertEquals("the library's rows go with it", 0, db.dump("item_also_listed").size)
                db.execSQL("INSERT INTO item_also_listed VALUES ('server-1', 'alice', '${hexId(1)}', '$ADDING')")
                db.execSQL("DELETE FROM item WHERE itemId = '${hexId(1)}'")
                assertEquals("and the item's rows go with it", 0, db.dump("item_also_listed").size)
            }
        }

    @Test
    fun theSchemaMatchesTheExportedEleven() =
        runBlocking {
            val file = File(folder.root, "schema.db")
            SchemaFile.create(file, 10) { it.fill() }
            migrate(file).close()

            val exported = SchemaFile.text()
            assertTrue(exported.contains("\"version\": 11"))
            val hash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(exported)!!.groupValues[1]
            raw(file) { db -> assertEquals(listOf(hash), db.strings("SELECT identity_hash FROM room_master_table")) }
        }

    @Test
    fun theMigrationSqlIsTheExportedSql() {
        val exported = SchemaFile.text()
        for (sql in Migration10To11.STATEMENTS.filter { it.startsWith("CREATE") }) {
            val asExported =
                sql
                    .replace("`item_also_listed`", "`\${TABLE_NAME}`")
                    .replace("ON `item` (", "ON `\${TABLE_NAME}` (")
            assertTrue("11.json declares: $sql", exported.contains(asExported.replace("\"", "\\\"")))
        }
    }

    @Test
    fun aVersionNineFileMigratesThroughEleven() =
        runBlocking {
            val file = File(folder.root, "nine.db")
            SchemaFile.create(file, 9) {
                it.execSQL("INSERT INTO active_account VALUES ('server-1', 'alice')")
                it.execSQL("INSERT INTO account_state VALUES ('server-1', 'alice', 1, NULL, NULL, $LAST_SUCCESS)")
                it.execSQL("INSERT INTO library VALUES ('server-1', 'alice', '${MOVIES.value}', 1)")
            }

            val repository = migrate(file)

            val row = repository.dao.libraries("server-1", "alice").single()
            assertEquals(LibraryEntity("server-1", "alice", MOVIES.value, fetched = true), row)
            assertTrue("9 to 10 still flags the re-read", repository.backfillPending(ALICE))
            repository.close()
        }

    private fun assertTrue(
        message: String,
        condition: () -> Boolean,
    ) = assertTrue(message, condition())

    private companion object {
        const val GENERATION = 1_234_567L
        const val LAST_SUCCESS = 1_699_990_000_000L
        const val HELD_UNTIL = 1_700_000_060_000L
        const val STAMP = 9L
        val ADDING = hexId(0xB003)
    }
}
