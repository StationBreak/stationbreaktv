// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import java.io.File
import kotlin.time.Instant

/**
 * Spec 23, D8, D5, D6, E2: the cache's second migration, version 8 to 9. The three version tables gain `name`, filled
 * from the current row of the same account and ID, and every account's content generation moves by one so persisted
 * days miss once. A file made from the committed `8.json` (or `7.json`, for the whole path) is opened by the current
 * code through the builder the app uses; Room then checks the migrated schema against the one exported as `9.json`.
 * The platform SQLite runs the same in `Migration8To9OnDeviceTest`. Invented names only.
 */
class Migration8To9Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val lastSuccess = 1_699_990_000_000L

    private fun SQLiteConnection.insert(
        table: String,
        values: String,
    ) = execSQL("INSERT INTO $table VALUES ($values)")

    // The rows both versions share: two accounts holding the same item, series and collection IDs, named apart.
    private fun SQLiteConnection.fillAccounts(
        studios: Boolean,
        withTombstone: Boolean = false,
    ) {
        val movies = "'${MOVIES.value}'"
        val people = listOf(Triple("alice", "Alice", GENERATION_A), Triple("bob", "Bob", GENERATION_B))
        for ((user, name, generation) in people) {
            val account = "'server-1', '$user'"
            insert("active_account", account)
            insert("account_state", "$account, 1, NULL, NULL, $lastSuccess")
            // Bob's studios re-read is still pending in the version 8 file: the migration must leave the flag as it is.
            val pending = if (studios) ", ${if (user == "bob") 1 else 0}" else ""
            insert("sync_meta", "$account, $generation, 1, 0$pending")
            insert("library", "$account, $movies, 1")
            val created = EPOCH.toEpochMilliseconds() - 60_000
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "seenFromMs, firstSeenMs) VALUES ($account, '${hexId(1)}', $movies, 'Movie', '$name Film', " +
                    "5400000, $created, $SEEN_EDITED, $SEEN_FIRST)",
            )
            val studioColumn = if (studios) ", studios" else ""
            val studioValue = if (studios) ", ''" else ""
            execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, genres, tags$studioColumn) VALUES ($account, '${hexId(1)}', " +
                    "$SEEN_FIRST, $SEEN_EDITED, $movies, 'Movie', 5400000, $created, '', ''$studioValue)",
            )
            val seriesStudios = if (studios) ", ''" else ""
            val seriesRow = "'show-1', $movies, '$name Show', NULL, '', '', NULL, $SEEN_EDITED, $SEEN_FIRST"
            insert("series", "$account, $seriesRow$seriesStudios")
            val seriesOld = "'show-1', $SEEN_FIRST, $SEEN_EDITED, $movies, NULL, '', '', NULL"
            insert("series_version", "$account, $seriesOld$seriesStudios")
            insert("collection", "$account, 'box-1', '$name Box Set 1 Collection', NULL, $SEEN_EDITED, $SEEN_FIRST")
            insert("collection_version", "$account, 'box-1', $SEEN_FIRST, $SEEN_EDITED, NULL")
        }
        if (withTombstone) {
            val account = "'server-1', 'alice'"
            val gone = EPOCH.toEpochMilliseconds()
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "deletedAtMs, seenFromMs, firstSeenMs) VALUES ($account, '${hexId(2)}', $movies, 'Movie', " +
                    "'Alice Gone Film', 5400000, 1, $gone, $SEEN_EDITED, $SEEN_FIRST)",
            )
            val studioColumn = if (studios) ", studios" else ""
            val studioValue = if (studios) ", ''" else ""
            execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, genres, tags$studioColumn) VALUES ($account, '${hexId(2)}', " +
                    "$SEEN_FIRST, $SEEN_EDITED, $movies, 'Movie', 5400000, 1, '', ''$studioValue)",
            )
            val seriesStudios = if (studios) ", ''" else ""
            val goneRow = "'show-2', $movies, 'Alice Gone Show', NULL, '', '', $gone, $SEEN_EDITED, $SEEN_FIRST"
            insert("series", "$account, $goneRow$seriesStudios")
            val goneOld = "'show-2', $SEEN_FIRST, $SEEN_EDITED, $movies, NULL, '', '', NULL"
            insert("series_version", "$account, $goneOld$seriesStudios")
            insert("collection", "$account, 'box-2', 'Alice Gone Box Set Collection', $gone, $SEEN_EDITED, $SEEN_FIRST")
            insert("collection_version", "$account, 'box-2', $SEEN_FIRST, $SEEN_EDITED, NULL")
        }
    }

    private fun writeVersion8(
        file: File,
        withTombstone: Boolean = false,
    ) = SchemaFile.create(file, 8) { it.fillAccounts(studios = true, withTombstone = withTombstone) }

    private fun writeVersion7(file: File) = SchemaFile.create(file, 7) { it.fillAccounts(studios = false) }

    private fun migrated(file: File): RoomLibraryRepository =
        TestLibraries.onDisk(folder.root, name = file.name, clock = clock, signedIn = emptyList())

    @Test
    fun versionRowsGainTheCurrentName() =
        runBlocking {
            val file = File(folder.root, "names.db")
            writeVersion8(file)

            val repository = migrated(file)

            val versions = repository.versions(ALICE).single()
            assertEquals("Alice Film", versions.name)
            assertEquals("Alice Show", repository.seriesVersions(ALICE).single().name)
            assertEquals("Alice Box Set 1 Collection", repository.collectionVersions(ALICE).single().name)
            repository.close()
        }

    @Test
    fun theBackfillTakesTheNameOfTheSameAccount() =
        runBlocking {
            val file = File(folder.root, "accounts.db")
            writeVersion8(file)

            val repository = migrated(file)

            assertEquals("Alice Film", repository.versions(ALICE).single().name)
            assertEquals("Bob Film", repository.versions(BOB).single().name)
            assertEquals("Alice Show", repository.seriesVersions(ALICE).single().name)
            assertEquals("Bob Show", repository.seriesVersions(BOB).single().name)
            assertEquals("Alice Box Set 1 Collection", repository.collectionVersions(ALICE).single().name)
            assertEquals("Bob Box Set 1 Collection", repository.collectionVersions(BOB).single().name)
            repository.close()
        }

    @Test
    fun everyAccountsGenerationMoves() =
        runBlocking {
            val file = File(folder.root, "generation.db")
            writeVersion8(file)

            val repository = migrated(file)

            assertEquals(GENERATION_A + 1, repository.contentGeneration(ALICE))
            assertEquals(GENERATION_B + 1, repository.contentGeneration(BOB))
            repository.close()
        }

    @Test
    fun aMigratedDatabaseAcceptsNewNamedVersions() =
        runBlocking {
            val file = File(folder.root, "after.db")
            writeVersion8(file)
            val repository = migrated(file)
            TestLibraries.signedIn(repository, listOf(ALICE))
            val before = repository.versions(ALICE).size

            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1).copy(name = "Alice Film Renamed"))
            repository.putSeries(ALICE, MOVIES, series("show-1", name = "Alice Show Renamed", rating = "TV-G"))

            val versions = repository.versions(ALICE)
            assertEquals(before + 1, versions.size)
            assertEquals("Alice Film", versions.last().name)
            assertEquals("Alice Show", repository.seriesVersions(ALICE).last().name)
            assertEquals("Alice Film Renamed", repository.currentRows(ALICE).single { it.itemId == hexId(1) }.name)
            repository.close()
        }

    @Test
    fun anEmptyDatabaseMigrates() =
        runBlocking {
            val file = File(folder.root, "empty.db")
            SchemaFile.create(file, 8) { }

            val repository = migrated(file)

            assertTrue(repository.dao.activeAccounts().isEmpty())
            assertTrue(repository.versions(ALICE).isEmpty())
            assertEquals(0L, repository.contentGeneration(ALICE))
            repository.close()
        }

    @Test
    fun aVersionSevenFileMigratesThroughNine() =
        runBlocking {
            val file = File(folder.root, "seven.db")
            writeVersion7(file)

            val repository = migrated(file)

            assertEquals("Alice Film", repository.versions(ALICE).single().name)
            assertEquals("Bob Show", repository.seriesVersions(BOB).single().name)
            assertEquals("Bob Box Set 1 Collection", repository.collectionVersions(BOB).single().name)
            // 7 to 8 flags the studios re-read and leaves the generation; 8 to 9 then moves it by one.
            assertTrue(repository.dao.metaRow("server-1", "alice")!!.backfillPending)
            assertEquals(GENERATION_A + 1, repository.contentGeneration(ALICE))
            repository.close()
        }

    @Test
    fun activeAccountAccountStateAndBackfillPendingAreUnchanged() =
        runBlocking {
            val file = File(folder.root, "untouched.db")
            writeVersion8(file)

            val repository = migrated(file)

            val active = listOf("alice", "bob").map { ActiveAccountEntity("server-1", it) }
            assertEquals(active, repository.dao.activeAccounts().sortedBy { it.userId })
            val idle = LibraryStatus(SyncState.Idle, Instant.fromEpochMilliseconds(lastSuccess))
            assertEquals(idle, repository.status(ALICE).first())
            assertEquals(lastSuccess, repository.dao.state("server-1", "bob")?.lastSuccessMs)
            assertFalse(repository.backfillPending(ALICE))
            assertTrue(repository.backfillPending(BOB))
            assertEquals(1, repository.dao.metaRow("server-1", "alice")!!.failedPasses)
            repository.close()
        }

    @Test
    fun aTombstonedItemsVersionsGetItsName() =
        runBlocking {
            val file = File(folder.root, "tombstone.db")
            writeVersion8(file, withTombstone = true)

            val repository = migrated(file)

            val gone = repository.versions(ALICE).single { it.itemId == hexId(2) }
            assertEquals("Alice Gone Film", gone.name)
            assertEquals("Alice Gone Show", repository.seriesVersions(ALICE).single { it.seriesId == "show-2" }.name)
            val box = repository.collectionVersions(ALICE).single { it.collectionId == "box-2" }
            assertEquals("Alice Gone Box Set Collection", box.name)
            repository.close()
        }

    @Test
    fun anAccountWithNoMetaRowGetsOneAboveEveryOldGeneration() =
        runBlocking {
            val file = File(folder.root, "nometa.db")
            SchemaFile.create(file, 8) {
                it.fillAccounts(studios = true)
                it.execSQL("DELETE FROM sync_meta WHERE userId = 'bob'")
            }

            val repository = migrated(file)

            // Bob had an account_state row and no sync_meta row, so a persisted day of his was keyed on generation 0
            // (an absent row reads 0). A row is made, seeded as generationSeed seeds one, and moved like the others.
            val bobs = repository.dao.metaRow("server-1", "bob")!!
            val seeded = (clock.nowMs shl LibraryDao.GENERATION_SHIFT) + 1
            assertEquals(seeded, bobs.contentGeneration)
            assertTrue(bobs.contentGeneration > maxOf(GENERATION_A, GENERATION_B) + 1)
            assertTrue(bobs.contentGeneration > 0L)
            assertFalse(bobs.backfillPending)
            assertEquals(0, bobs.failedPasses)
            assertEquals(GENERATION_A + 1, repository.contentGeneration(ALICE))
            repository.close()
        }

    @Test
    fun theSchemaMatchesTheExportedNine() =
        runBlocking {
            val file = File(folder.root, "schema.db")
            writeVersion8(file)
            migrated(file).also {
                it.dao.activeAccounts() // Room opens the file, and so migrates and checks it, on its first query.
                it.close()
            }

            val exported = SchemaFile.text()
            val hash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(exported)!!.groupValues[1]
            val connection = BundledSQLiteDriver().open(file.path)
            try {
                val ids =
                    connection.prepare("SELECT identity_hash FROM room_master_table").use { st ->
                        buildList { while (st.step()) add(st.getText(0)) }
                    }
                assertEquals(listOf(hash), ids)
                for (table in listOf("item_version", "series_version", "collection_version")) {
                    val columns =
                        connection.prepare("PRAGMA table_info(`$table`)").use { st ->
                            buildList { while (st.step()) add(Triple(st.getText(1), st.getText(2), st.getLong(3))) }
                        }
                    val name = columns.single { it.first == "name" }
                    assertEquals("$table.name is TEXT", "TEXT", name.second)
                    assertEquals("$table.name is nullable", 0L, name.third)
                }
            } finally {
                connection.close()
            }
        }

    private companion object {
        const val GENERATION_A = 1_234_567L
        const val GENERATION_B = 7_654_321L
        const val SEEN_FIRST = 1_000L
        const val SEEN_EDITED = 2_000L
    }
}
