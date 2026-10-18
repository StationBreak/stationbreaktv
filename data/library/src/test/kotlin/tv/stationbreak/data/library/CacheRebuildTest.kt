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
                "series",
                "series_version",
                "collection",
                "collection_version",
                "collection_member",
                "collection_sync",
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
    fun theSchemaIsVersion6WithThePremiereDateExactMillisecondsAndTheSeasonId() {
        assertEquals(6, LibraryDatabase.VERSION)
        val columns = SchemaFile.columnNames()
        assertTrue("premiereDate" in columns)
        assertTrue("runtimeMs" in columns)
        assertTrue("runtimeNanos" !in columns)
        assertTrue("seasonId" in columns)
    }

    @Test
    fun theSeriesTablesHoldTheirFieldsAndCascadeFromLibraryAndSeries() {
        val text = SchemaFile.text()
        assertTrue(text.contains("\"tableName\": \"series\""))
        assertTrue(text.contains("\"tableName\": \"series_version\""))
        // series: cascades from library, indexed for that cascade and for the tombstone purge.
        assertTrue(text.contains("`serverId`, `userId`, `seriesId`, `seenFromMs`"))
        assertTrue(text.contains("(`serverId`, `userId`, `libraryId`)"))
        assertTrue(text.contains("(`serverId`, `userId`, `deletedAtMs`)"))
        // series_version: the purge of versions superseded long ago.
        assertTrue(text.contains("(`serverId`, `userId`, `supersededAtMs`)"))
        assertTrue(text.contains("\"table\": \"series\""))
    }

    @Test
    fun theCollectionTablesHoldTheirFieldsAndCascadeFromCollection() {
        val text = SchemaFile.text()
        for (table in listOf("collection", "collection_version", "collection_member", "collection_sync")) {
            assertTrue("$table missing", text.contains("\"tableName\": \"$table\""))
        }
        // The primary keys of the three history tables, and the one row per account of collection_sync.
        assertTrue(text.contains("`serverId`, `userId`, `collectionId`, `seenFromMs`"))
        assertTrue(text.contains("`serverId`, `userId`, `collectionId`, `memberId`, `seenFromMs`"))
        assertTrue(text.contains("PRIMARY KEY(`serverId`, `userId`)"))
        // collection_version and collection_member cascade from collection; collection has no library foreign key.
        for (table in listOf("collection_version", "collection_member")) {
            val block = tableBlock(text, table)
            assertTrue("$table needs a foreign key to collection", block.contains("\"table\": \"collection\""))
            assertTrue("$table needs ON DELETE CASCADE", block.contains("\"onDelete\": \"CASCADE\""))
        }
        val collection = tableBlock(text, "collection")
        assertTrue("collection must not reference library", !collection.contains("\"table\": \"library\""))
        val columns = SchemaFile.columnNames()
        listOf("memberKind", "memberId", "collectionsSinceMs", "lastRelistMs").forEach {
            assertTrue("$it missing", it in columns)
        }
    }

    // The JSON of one table's entry in the exported schema, up to the next table.
    private fun tableBlock(
        text: String,
        table: String,
    ): String {
        val start = text.indexOf("\"tableName\": \"$table\"")
        val next = text.indexOf("\"tableName\"", start + 1)
        return text.substring(start, if (next < 0) text.length else next)
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
    fun aVersion6FileOpenedByVersion5CodeIsDroppedAndRebuiltEmpty() =
        runBlocking {
            // The rollback path (the app goes back from v6 to v5): the file is NEWER than the code. A v5 builder with
            // the same destructive-on-downgrade setting the cache uses must drop every table, not throw.
            val context = TestContext(folder.root)
            val current = TestLibraries.onDisk(folder.root, name = "downgrade.db")
            current.select(ALICE, setOf(MOVIES))
            current.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, current.stored(ALICE).size)
            current.close()

            val older =
                Room
                    .databaseBuilder(context, OlderSchemaDatabase::class.java, "downgrade.db")
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(TEST_QUERY_CONTEXT)
                    .withDestructiveFallbacks() // The cache's own settings, so a change there changes this test.
                    .build()
            older.otherDao().put(OtherRow("v5 works"))
            older.close()

            // Back on v6 the cache is empty and usable: the v6 data did not survive the round trip.
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

    @Test
    fun aRealVersion4FileFromSpec09IsDroppedAndRebuiltEmptyWithoutThrowing() =
        runBlocking {
            // Version 4 shipped (in a debug build) with the schema in the committed 4.json. A file made from exactly
            // that schema, opened by v6 code, must be rebuilt empty, as the v3 file was.
            val v4 = File(folder.root, "spec09.db")
            writeSchemaFile(v4, 4, "NULL, NULL, NULL, NULL, 5400000, 1, NULL, NULL, NULL, NULL, 1, 1")

            val repository = TestLibraries.onDisk(folder.root, name = "spec09.db")

            assertEquals(RowCounts.NONE, repository.rowCounts())
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, repository.stored(ALICE).size)
            repository.close()
        }

    @Test
    fun aRealVersion5FileFromSpec10Unit1IsDroppedAndRebuiltEmptyWithoutThrowing() =
        runBlocking {
            // Version 5 shipped (in a debug build) with the schema in the committed 5.json. A file made from exactly
            // that schema, opened by v6 code, must be rebuilt empty, as the v3 and v4 files were (Vimes L8).
            val v5 = File(folder.root, "spec10-unit1.db")
            writeSchemaFile(v5, 5, "NULL, NULL, NULL, NULL, NULL, 5400000, 1, NULL, NULL, NULL, NULL, 1, 1")

            val repository = TestLibraries.onDisk(folder.root, name = "spec10-unit1.db")

            assertEquals(RowCounts.NONE, repository.rowCounts())
            repository.useAccount(ALICE)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            assertEquals(1, repository.stored(ALICE).size)
            repository.close()
        }

    private fun writeVersion3File(file: File) {
        val tail = "NULL, NULL, NULL, NULL, 5400000, 1, NULL, NULL, NULL, NULL"
        writeSchemaFile(file, 3, tail)
    }

    // Creates the database of schema [version] from its committed file (its CREATE statements, its identity hash,
    // user_version), with one library and one item in it, so there is something to lose. [itemTail] is the item
    // row's columns after its name.
    private fun writeSchemaFile(
        file: File,
        version: Int,
        itemTail: String,
    ) {
        val text = File("schemas/tv.stationbreak.data.library.LibraryDatabase/$version.json").readText()
        assertTrue(text.contains("\"version\": $version"))
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
                "INSERT INTO item VALUES ('server-1', 'alice', 'x', 'lib-movies', 'Movie', 'X', $itemTail)",
            )
            connection.execSQL("PRAGMA user_version = $version")
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
