// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Spec 18 section 2 (Review Focus 4), on the platform SQLite of the API 25 emulator (SQLite 3.9, like Fire OS 6's):
 * Room's `MigrationTestHelper` creates a version 7 file from the committed `7.json`, runs [Migration7To8] and checks
 * the result against the committed `8.json`, unknown tables included. The migrated file then opens through the same
 * `create` the app uses, with no server: the signed-in account, the items, their history and the last success are
 * there, and every account is flagged for the studios re-read. `Migration7To8Test` runs the same on the JVM through
 * Room's own open-time check. Invented names only.
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val keys = "'device-test-server', 'device-test-user'"
    private val other = "'device-test-server', 'other-test-user'"
    private val library = ItemId("2222222222222222222222222222222a")
    private val lastSuccess = Instant.fromEpochMilliseconds(1_700_000_000_000)
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

    private fun SupportSQLiteDatabase.fillAsVersion7() {
        val lib = "'${library.value}'"
        execSQL("INSERT INTO active_account VALUES ($keys)")
        execSQL("INSERT INTO account_state VALUES ($keys, 1, NULL, NULL, ${lastSuccess.toEpochMilliseconds()})")
        execSQL("INSERT INTO account_state VALUES ($other, 1, NULL, NULL, 1)")
        execSQL("INSERT INTO sync_meta VALUES ($keys, $GENERATION, 0, 0)")
        execSQL("INSERT INTO library VALUES ($keys, $lib, 1)")
        for (n in 1..2) {
            val id = "'1111111111111111111111111111111$n'"
            execSQL(
                "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                    "seenFromMs, firstSeenMs) VALUES ($keys, $id, $lib, 'Movie', 'Invented $n', 5400000, " +
                    "1600000000000, 2000, 1000)",
            )
            execSQL("INSERT INTO item_genre VALUES ($keys, $id, 0, 'Drama')")
            execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, genres, tags) VALUES ($keys, $id, 1000, 2000, $lib, 'Movie', " +
                    "5400000, 1600000000000, '', '')",
            )
        }
        execSQL("INSERT INTO series VALUES ($keys, 'invented-series', $lib, 'Invented', NULL, '', '', NULL, 1, 1)")
    }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long> =
        query(sql).use { cursor ->
            val values = ArrayList<Long>()
            while (cursor.moveToNext()) values += cursor.getLong(0)
            values
        }

    @Test
    fun aVersion7FileMigratesToTheExportedSchemaAndAnswersWithNoServer() {
        helper.createDatabase(name, 7).apply {
            fillAsVersion7()
            close()
        }

        val seed = now.toEpochMilliseconds() shl LibraryDao.GENERATION_SHIFT
        val migrated = helper.runMigrationsAndValidate(name, 8, true, Migration7To8 { seed })

        assertEquals(listOf(1L, 1L), migrated.longs("SELECT backfillPending FROM sync_meta ORDER BY userId"))
        val generations = migrated.longs("SELECT contentGeneration FROM sync_meta ORDER BY userId")
        assertEquals(listOf(GENERATION, seed), generations)
        assertEquals(listOf(2L), migrated.longs("SELECT COUNT(*) FROM item_version WHERE studios = ''"))
        assertEquals(listOf(1L), migrated.longs("SELECT COUNT(*) FROM series WHERE studios = ''"))
        migrated.close()

        val repository = RoomLibraryRepository.create(context, clock)
        runBlocking {
            val active = ActiveAccountEntity(account.server.value, account.user.value)
            assertEquals(listOf(active), repository.dao.activeAccounts())
            val items = repository.ruleSnapshot(account, setOf(library), now).items
            assertEquals(2, items.size)
            assertEquals(listOf(listOf("Drama"), listOf("Drama")), items.map { it.genres })
            assertEquals(LibraryStatus(SyncState.Idle, lastSuccess), repository.status(account).first())
        }
        repository.close()
    }

    private companion object {
        const val GENERATION = 4_242L
    }
}
