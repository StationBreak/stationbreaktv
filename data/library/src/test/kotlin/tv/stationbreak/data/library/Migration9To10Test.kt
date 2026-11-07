// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import java.io.File
import kotlin.time.Instant

/**
 * Spec 26, Part 3: the cache's third migration, version 9 to 10. `item` and `series` gain `providerIds` (`''`), and
 * `library` gains `heldUntilMs` and `addToken` (null). Every account with a saved library is flagged for the studios
 * re-read, which then writes the provider ids; an account with an `account_state` row and no `sync_meta` row gets
 * one. Nothing else moves: rows (tombstones included), versions, `account_state` and the content generation. A file
 * made from the committed `9.json` is opened through the builder the app uses, and Room checks the result against
 * `10.json`. The platform SQLite runs the same in `Migration9To10OnDeviceTest`. Invented names only.
 */
class Migration9To10Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val lastSuccess = 1_699_990_000_000L

    private fun SQLiteConnection.fill() {
        val movies = "'${MOVIES.value}'"
        val people = listOf(Triple("alice", "Alice", GENERATION_A), Triple("bob", "Bob", GENERATION_B))
        for ((user, name, generation) in people) {
            val account = "'server-1', '$user'"
            execSQL("INSERT INTO active_account VALUES ($account)")
            execSQL("INSERT INTO account_state VALUES ($account, 1, NULL, NULL, $lastSuccess)")
            if (user == "alice") execSQL("INSERT INTO sync_meta VALUES ($account, $generation, 1, 0, 0)")
            execSQL("INSERT INTO library VALUES ($account, $movies, 1)")
            val created = EPOCH.toEpochMilliseconds() - 60_000
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "seenFromMs, firstSeenMs) VALUES ($account, '${hexId(1)}', $movies, 'Movie', '$name Film', " +
                    "5400000, $created, $SEEN_EDITED, $SEEN_FIRST)",
            )
            execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, genres, tags, studios, name) VALUES ($account, '${hexId(1)}', " +
                    "$SEEN_FIRST, $SEEN_EDITED, $movies, 'Movie', 5400000, $created, '', '', '', '$name Film')",
            )
            execSQL(
                "INSERT INTO series (serverId, userId, seriesId, libraryId, name, genres, tags, seenFromMs, " +
                    "firstSeenMs, studios) VALUES ($account, 'show-1', $movies, '$name Show', '', '', " +
                    "$SEEN_EDITED, $SEEN_FIRST, '')",
            )
        }
        // Alice's tombstones: an item and a series the server no longer had.
        val alice = "'server-1', 'alice'"
        val goneAt = EPOCH.toEpochMilliseconds()
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, deletedAtMs, " +
                "seenFromMs, firstSeenMs) VALUES ($alice, '${hexId(2)}', $movies, 'Movie', 'Alice Gone Film', " +
                "5400000, 1, $goneAt, $SEEN_EDITED, $SEEN_FIRST)",
        )
        execSQL(
            "INSERT INTO series (serverId, userId, seriesId, libraryId, name, genres, tags, deletedAtMs, seenFromMs, " +
                "firstSeenMs, studios) VALUES ($alice, 'show-2', $movies, 'Alice Gone Show', '', '', $goneAt, " +
                "$SEEN_EDITED, $SEEN_FIRST, '')",
        )
    }

    private fun migrated(file: File): RoomLibraryRepository =
        TestLibraries.onDisk(folder.root, name = file.name, clock = clock, signedIn = emptyList())

    @Test
    fun migration9To10_from9Json_withTombstones_keepsRows_setsBackfill_insertsMissingSyncMeta() =
        runBlocking {
            val file = File(folder.root, "nine.db")
            SchemaFile.create(file, 9) { it.fill() }

            val repository = migrated(file)

            // Every row is kept, the tombstones too, with no provider ids yet.
            val alice = repository.currentRows(ALICE)
            assertEquals(listOf(hexId(1), hexId(2)), alice.map { it.itemId }.sorted())
            assertEquals(goneAt(), alice.single { it.itemId == hexId(2) }.deletedAtMs)
            assertTrue(alice.all { it.providerIds == "" })
            assertEquals(1, repository.currentRows(BOB).size)
            val series = repository.currentSeries(ALICE)
            assertEquals(listOf("show-1", "show-2"), series.map { it.seriesId }.sorted())
            assertTrue(series.all { it.providerIds == "" })
            assertEquals("Alice Film", repository.versions(ALICE).single().name)
            // Every library is chosen: no hold, no add, still fetched.
            val libraries = repository.dao.libraries("server-1", "alice")
            assertEquals(listOf(LibraryEntity("server-1", "alice", MOVIES.value, fetched = true)), libraries)
            // The re-read is flagged for both, and Bob, who had no sync_meta row, now has one.
            assertTrue(repository.backfillPending(ALICE))
            assertTrue(repository.backfillPending(BOB))
            val bobs = repository.dao.metaRow("server-1", "bob")!!
            assertEquals(clock.nowMs shl LibraryDao.GENERATION_SHIFT, bobs.contentGeneration)
            assertEquals(0, bobs.failedPasses)
            // Alice's generation and pass count do not move: provider ids are no part of a day.
            assertEquals(GENERATION_A, repository.contentGeneration(ALICE))
            assertEquals(1, repository.dao.metaRow("server-1", "alice")!!.failedPasses)
            // account_state and the signed-in accounts are as they were.
            val idle = LibraryStatus(SyncState.Idle, Instant.fromEpochMilliseconds(lastSuccess))
            assertEquals(idle, repository.status(ALICE).first())
            assertEquals(idle, repository.status(BOB).first())
            assertEquals(2, repository.dao.activeAccounts().size)
            repository.close()
        }

    @Test
    fun aMigratedDatabaseStoresProviderIdsAndSelections() =
        runBlocking {
            val file = File(folder.root, "after.db")
            SchemaFile.create(file, 9) { it.fill() }
            val repository = migrated(file)
            TestLibraries.signedIn(repository, listOf(ALICE))

            val film = sitem(1).copy(name = "Alice Film", providerIds = mapOf("imdb" to "tt0000001"))
            repository.put(ALICE, MOVIES, film)
            val before = repository.contentGeneration(ALICE)
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 5L), nowMs = clock.nowMs)

            assertEquals("imdb=tt0000001", repository.currentRows(ALICE).single { it.itemId == hexId(1) }.providerIds)
            assertEquals(
                5L,
                repository.dao
                    .libraries("server-1", "alice")
                    .single { it.libraryId == SHOWS.value }
                    .addToken,
            )
            assertEquals(before, repository.contentGeneration(ALICE))
            repository.close()
        }

    @Test
    fun anEmptyDatabaseMigrates() =
        runBlocking {
            val file = File(folder.root, "empty.db")
            SchemaFile.create(file, 9) { }

            val repository = migrated(file)

            assertTrue(repository.dao.activeAccounts().isEmpty())
            assertNull(repository.dao.metaRow("server-1", "alice"))
            repository.close()
        }

    @Test
    fun aVersionSevenFileMigratesThroughTen() =
        runBlocking {
            val file = File(folder.root, "seven.db")
            SchemaFile.create(file, 7) {
                it.execSQL("INSERT INTO active_account VALUES ('server-1', 'alice')")
                it.execSQL("INSERT INTO account_state VALUES ('server-1', 'alice', 1, NULL, NULL, $lastSuccess)")
                it.execSQL("INSERT INTO library VALUES ('server-1', 'alice', '${MOVIES.value}', 1)")
            }

            val repository = migrated(file)

            assertTrue(repository.backfillPending(ALICE))
            assertNull(
                repository.dao
                    .libraries("server-1", "alice")
                    .single()
                    .heldUntilMs,
            )
            repository.close()
        }

    @Test
    fun theSchemaMatchesTheExportedTen() =
        runBlocking {
            val file = File(folder.root, "schema.db")
            SchemaFile.create(file, 9) { it.fill() }
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
                val version =
                    connection.prepare("PRAGMA user_version").use { st ->
                        st.step()
                        st.getLong(0)
                    }
                assertEquals(10L, version)
            } finally {
                connection.close()
            }
        }

    private fun goneAt() = EPOCH.toEpochMilliseconds()

    private companion object {
        const val GENERATION_A = 1_234_567L
        const val GENERATION_B = 7_654_321L
        const val SEEN_FIRST = 1_000L
        const val SEEN_EDITED = 2_000L
    }
}
