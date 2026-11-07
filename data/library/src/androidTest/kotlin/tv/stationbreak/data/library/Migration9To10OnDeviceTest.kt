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
import org.junit.Assert.assertNull
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
 * Spec 26, Part 3: version 9 to 10 on the platform SQLite of the API 25 emulator (SQLite 3.9, like Fire OS 6's).
 * Room's `MigrationTestHelper` creates the file from the committed `9.json`, runs [Migration9To10] and checks the
 * result against the committed `10.json`. The rows (a tombstone included) are kept with no provider ids and no hold or add,
 * every account is flagged for the re-read, an account with no `sync_meta` row gets one, and no generation moves. The
 * migrated file then opens through the same `create` the app uses, and the one selection writer runs on it.
 * `Migration9To10Test` runs the same on the JVM. Invented names only.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val keys = "'device-test-server', 'device-test-user'"
    private val other = "'device-test-server', 'other-test-user'"
    private val library = ItemId("2222222222222222222222222222222a")
    private val added = ItemId("2222222222222222222222222222222b")
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

    // A live item and series under each of two accounts, and a tombstoned item under the first. Only the first account
    // has a sync_meta row: the migration must make the second one's.
    private fun SupportSQLiteDatabase.fill() {
        val lib = "'${library.value}'"
        for ((who, label) in listOf(keys to "First", other to "Second")) {
            execSQL("INSERT INTO account_state VALUES ($who, 1, NULL, NULL, 1)")
            if (label == "First") execSQL("INSERT INTO sync_meta VALUES ($who, $GENERATION, 0, 0, 0)")
            execSQL("INSERT INTO library VALUES ($who, $lib, 1)")
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "seenFromMs, firstSeenMs) VALUES ($who, '11111111111111111111111111111111', $lib, 'Movie', " +
                    "'$label Film', 5400000, 1600000000000, 2000, 1000)",
            )
            execSQL(
                "INSERT INTO series (serverId, userId, seriesId, libraryId, name, genres, tags, seenFromMs, " +
                    "firstSeenMs, studios) VALUES ($who, 'show-1', $lib, '$label Show', '', '', 2000, 1000, '')",
            )
        }
        execSQL(
            "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, deletedAtMs, " +
                "seenFromMs, firstSeenMs) VALUES ($keys, '11111111111111111111111111111112', $lib, 'Movie', " +
                "'First Gone Film', 5400000, 1600000000000, 3000, 2000, 1000)",
        )
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> =
        query(sql).use { cursor ->
            val values = ArrayList<String?>()
            while (cursor.moveToNext()) values += if (cursor.isNull(0)) null else cursor.getString(0)
            values
        }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long?> =
        query(sql).use { cursor ->
            val values = ArrayList<Long?>()
            while (cursor.moveToNext()) values += if (cursor.isNull(0)) null else cursor.getLong(0)
            values
        }

    @Test
    fun rowsAreKeptWithNoProviderIdsAndNoHoldsAndEveryAccountIsFlagged() {
        helper.createDatabase(name, 9).apply {
            fill()
            close()
        }

        // runMigrationsAndValidate compares the migrated schema with 10.json, unknown tables and columns included.
        val migrated = helper.runMigrationsAndValidate(name, 10, true, Migration9To10 { SEED })

        assertEquals(listOf("", "", ""), migrated.strings("SELECT providerIds FROM item ORDER BY userId, itemId"))
        val deleted = migrated.longs("SELECT deletedAtMs FROM item ORDER BY userId DESC, itemId")
        assertEquals(listOf(null, null, 3000L), deleted)
        assertEquals(listOf("", ""), migrated.strings("SELECT providerIds FROM series ORDER BY userId"))
        assertEquals(listOf(null, null), migrated.longs("SELECT heldUntilMs FROM library ORDER BY userId"))
        assertEquals(listOf(null, null), migrated.longs("SELECT addStamp FROM library ORDER BY userId"))
        assertEquals(listOf(1L, 1L), migrated.longs("SELECT backfillPending FROM sync_meta ORDER BY userId"))
        val generations = migrated.longs("SELECT contentGeneration FROM sync_meta ORDER BY userId")
        assertEquals(listOf(GENERATION, SEED), generations)
        migrated.close()
    }

    @Test
    fun theMigratedFileOpensInTheAppAndTakesASelection() {
        helper.createDatabase(name, 9).apply {
            fill()
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, Migration9To10 { SEED }).close()

        val repository = RoomLibraryRepository.create(context, clock)
        runBlocking {
            repository.useAccount(account)
            val held = now.toEpochMilliseconds() + 60_000
            val nowMs = now.toEpochMilliseconds()
            repository.applySelection(account, setOf(added), mapOf(added to 1L), mapOf(library to held), nowMs)
            val rows = repository.dao.libraries("device-test-server", "device-test-user").associateBy { it.libraryId }
            assertEquals(held, rows.getValue(library.value).heldUntilMs)
            assertEquals(1L, rows.getValue(added.value).addStamp)
            assertNull(rows.getValue(added.value).heldUntilMs)
            assertEquals(GENERATION, repository.contentGeneration(account))
        }
        repository.close()
    }

    private companion object {
        const val GENERATION = 4_242L
        val SEED = 1L shl LibraryDao.GENERATION_SHIFT
    }
}
