// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.data.library.LibraryDatabases.withDestructiveFallbacks

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
            setOf("library", "item", "item_genre", "item_tag", "checkpoint", "account_state", "active_account"),
            SchemaFile.tableNames().toSet(),
        )
        assertTrue(SchemaFile.text().contains("\"version\": ${LibraryDatabase.VERSION}"))
    }

    @Test
    fun theSchemaIsVersion3WithThePremiereDateAndExactMillisecondColumns() {
        assertEquals(3, LibraryDatabase.VERSION)
        val columns = SchemaFile.columnNames()
        assertTrue("premiereDate" in columns)
        assertTrue("runtimeMs" in columns)
        assertTrue("runtimeNanos" !in columns)
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
    fun aVersion3FileOpenedByVersion2CodeIsDroppedAndRebuiltEmpty() =
        runBlocking {
            // The rollback path (the app goes back from v3 to v2): the file is NEWER than the code. A v2 builder with
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

            // Back on v3 the cache is empty and usable: the v3 data did not survive the round trip.
            val again = TestLibraries.onDisk(folder.root, name = "downgrade.db")
            assertEquals(RowCounts.NONE, again.rowCounts())
            again.useAccount(ALICE)
            again.select(ALICE, setOf(MOVIES))
            assertEquals(setOf(MOVIES), again.pendingLibraries(ALICE))
            again.close()
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
