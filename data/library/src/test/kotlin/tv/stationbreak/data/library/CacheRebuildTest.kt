// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.data.library.LibraryDatabases.withDestructiveFallbacks
import java.io.File

/**
 * Spec 06 task 2.11: the database is a cache. A schema change drops it and it is fetched again, and the schema is
 * exported so a change shows in review.
 */
class CacheRebuildTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theExportedSchemaFileExistsAndNamesTheTables() {
        assertTrue("missing ${SchemaFile.file.path}", SchemaFile.file.isFile)
        assertEquals(
            setOf(
                "library",
                "item",
                "item_version",
                "item_genre",
                "item_tag",
                "checkpoint",
                "account_state",
                "active_account",
            ),
            SchemaFile.tableNames().toSet(),
        )
        assertTrue(SchemaFile.text().contains("\"version\": ${LibraryDatabase.VERSION}"))
    }

    @Test
    fun theSchemaIsVersion4WithThePremiereDateAndExactMillisecondColumns() {
        assertEquals(4, LibraryDatabase.VERSION)
        val columns = SchemaFile.columnNames()
        assertTrue("premiereDate" in columns)
        assertTrue("runtimeMs" in columns)
        assertTrue("runtimeNanos" !in columns)
    }

    @Test
    fun theHistoryTableHoldsTheScheduleFieldsTheStampsAndTheEncodedListsAndCascadesFromItem() {
        val text = SchemaFile.text()
        val columns = SchemaFile.columnNames()
        listOf("seenFromMs", "firstSeenMs", "supersededAtMs", "genres", "tags").forEach {
            assertTrue("$it missing", it in columns)
        }
        assertTrue("item_version needs a cascading foreign key to item", text.contains("\"onDelete\": \"CASCADE\""))
        assertTrue(text.contains("`serverId`, `userId`, `itemId`, `seenFromMs`"))
        assertTrue(text.contains("(`serverId`, `userId`, `supersededAtMs`)"))
        assertTrue(text.contains("(`serverId`, `userId`, `deletedAtMs`)"))
    }

    @Test
    fun theDatabaseVersionIsExportedAndNotZero() {
        assertTrue(LibraryDatabase.VERSION >= 1)
    }

    @Test
    fun aDatabaseFromADifferentSchemaIsDroppedAndRebuiltEmpty() =
        runBlocking {
            // A "future" schema stands in for any schema change: a file the current schema has no migration for.
            val context = TestContext(folder.root)
            val other =
                Room
                    .databaseBuilder(context, OtherSchemaDatabase::class.java, "rebuild.db")
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(TEST_QUERY_CONTEXT)
                    .build()
            other.otherDao().put(OtherRow("kept?"))
            other.close()

            val repository =
                RoomLibraryRepository.testing(
                    LibraryDatabases
                        .builder(context, "rebuild.db")
                        .setDriver(BundledSQLiteDriver())
                        .setQueryCoroutineContext(TEST_QUERY_CONTEXT),
                    FakeClock(),
                )

            // It opens (no crash, no migration error), is empty, and is usable.
            assertEquals(RowCounts.NONE, repository.rowCounts())
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, repository.stored(ALICE).size)
            repository.close()
        }

    @Test
    fun aDatabaseFromAnOlderSchemaIsDroppedAndRebuiltEmpty() =
        runBlocking {
            // The upgrade path (the file is older than the code), which version 2 is the first to take: the cache has
            // no migration, so the old file is dropped and fetched again.
            val context = TestContext(folder.root)
            val older =
                Room
                    .databaseBuilder(context, OlderSchemaDatabase::class.java, "upgrade.db")
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(TEST_QUERY_CONTEXT)
                    .build()
            older.otherDao().put(OtherRow("kept?"))
            older.close()

            val repository =
                RoomLibraryRepository.testing(
                    LibraryDatabases
                        .builder(context, "upgrade.db")
                        .setDriver(BundledSQLiteDriver())
                        .setQueryCoroutineContext(TEST_QUERY_CONTEXT),
                    FakeClock(),
                )

            assertEquals(RowCounts.NONE, repository.rowCounts())
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            assertEquals(setOf(MOVIES), repository.pendingLibraries(ALICE))
            repository.close()
        }

    @Test
    fun aVersion4FileOpenedByVersion3CodeIsDroppedAndRebuiltEmpty() =
        runBlocking {
            // The rollback path (the app goes back from v4 to v3): the file is NEWER than the code. A v3 builder with
            // the same destructive-on-downgrade setting the cache uses must drop every table, not throw.
            val context = TestContext(folder.root)
            val current = TestLibraries.onDisk(folder.root, name = "downgrade.db")
            current.select(ALICE, setOf(MOVIES))
            current.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, current.stored(ALICE).size)
            current.close()

            val v2 =
                Room
                    .databaseBuilder(context, OlderSchemaDatabase::class.java, "downgrade.db")
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(TEST_QUERY_CONTEXT)
                    .withDestructiveFallbacks() // The cache's own settings, so a change there changes this test.
                    .build()
            v2.otherDao().put(OtherRow("v2 works"))
            v2.close()

            // Back on v4 the cache is empty and usable: the v4 data did not survive the round trip.
            val again = TestLibraries.onDisk(folder.root, name = "downgrade.db")
            assertEquals(RowCounts.NONE, again.rowCounts())
            again.useAccount(ALICE)
            again.select(ALICE, setOf(MOVIES))
            assertEquals(setOf(MOVIES), again.pendingLibraries(ALICE))
            again.close()
        }

    @Test
    fun aRealVersion3FileFromUnit1IsDroppedAndRebuiltEmptyWithoutThrowing() =
        runBlocking {
            // Version 3 shipped (in a debug build) with the schema in the committed 3.json: a file made from exactly
            // that schema, opened by the current code, must be rebuilt. Room throws "cannot verify the data
            // integrity" for a changed schema under an unchanged version, which the fallback does not catch, so the
            // version moved to 4 instead of amending 3 (Vimes H-1).
            val v3 = File(folder.root, "unit1.db")
            writeVersion3File(v3)

            val repository = TestLibraries.onDisk(folder.root, name = "unit1.db")

            assertEquals(RowCounts.NONE, repository.rowCounts())
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, repository.stored(ALICE).size)
            repository.close()
        }

    // Creates the Unit 1 database from the committed 3.json (its CREATE statements, its identity hash, user_version 3),
    // with one library and one item in it, so there is something to lose.
    private fun writeVersion3File(file: File) {
        val text = File("schemas/tv.stationbreak.data.library.LibraryDatabase/3.json").readText()
        assertTrue(text.contains("\"version\": 3"))
        val hash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(text)!!.groupValues[1]
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            var table = ""
            Regex("\"(tableName|createSql)\":\\s*\"([^\"]*)\"").findAll(text).forEach { match ->
                val (key, value) = match.destructured
                if (key == "tableName") {
                    table = value
                } else {
                    connection.execSQL(value.replace("\${TABLE_NAME}", table))
                }
            }
            val master = "room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
            connection.execSQL("CREATE TABLE IF NOT EXISTS $master")
            connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')")
            connection.execSQL("INSERT INTO library VALUES ('server-1', 'alice', 'lib-movies', 1)")
            connection.execSQL(
                "INSERT INTO item VALUES ('server-1', 'alice', 'x', 'lib-movies', 'Movie', 'X', " +
                    "NULL, NULL, NULL, NULL, 5400000, 1, NULL, NULL, NULL, NULL)",
            )
            connection.execSQL("PRAGMA user_version = 3")
        } finally {
            connection.close()
        }
    }

    @Test
    fun aDatabaseFromTheCurrentSchemaIsKept() =
        runBlocking {
            val first = TestLibraries.onDisk(folder.root, name = "keep.db")
            first.select(ALICE, setOf(MOVIES))
            first.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            first.close()

            val second = TestLibraries.onDisk(folder.root, name = "keep.db")

            assertEquals(1, second.stored(ALICE).size)
            second.close()
        }
}

// A different schema at a higher version, written to the same file name the cache then opens.
@Entity(tableName = "other_table")
internal data class OtherRow(
    @PrimaryKey val id: String,
)

@androidx.room.Dao
internal interface OtherDao {
    @androidx.room.Insert
    suspend fun put(row: OtherRow)
}

@Database(entities = [OtherRow::class], version = LibraryDatabase.VERSION + 1, exportSchema = false)
internal abstract class OtherSchemaDatabase : RoomDatabase() {
    abstract fun otherDao(): OtherDao
}

// The same, at the version before the current one.
@Database(entities = [OtherRow::class], version = LibraryDatabase.VERSION - 1, exportSchema = false)
internal abstract class OlderSchemaDatabase : RoomDatabase() {
    abstract fun otherDao(): OtherDao
}
