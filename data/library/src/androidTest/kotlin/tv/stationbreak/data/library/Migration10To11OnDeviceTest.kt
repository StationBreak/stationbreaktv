// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.os.Looper
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Spec 26, Part 4: version 10 to 11 on the platform SQLite of the API 25 emulator (SQLite 3.9, like Fire OS 6's), at a
 * synthetic 50,000-item library. Room's `MigrationTestHelper` makes the file from the committed `10.json`, runs
 * [Migration10To11] and checks the result against the committed `11.json` (every table, column, index and foreign key
 * by name). The same file, opened through the `create` the app uses on a thread that is not the main one, migrates
 * in under 3 s: the series index over the items is the migration's only real cost. `Migration10To11Test` runs the
 * rest on the JVM. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val keys = "'device-test-server', 'device-test-user'"
    private val library = "3333333333333333333333333333333a"
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

    // One account with one chosen library of [ITEMS] episodes across [SERIES] series, in one transaction.
    private fun SupportSQLiteDatabase.fill() {
        execSQL("INSERT INTO active_account VALUES ($keys)")
        execSQL("INSERT INTO account_state VALUES ($keys, 1, NULL, NULL, 1)")
        execSQL("INSERT INTO sync_meta VALUES ($keys, $GENERATION, 0, 0, 0)")
        execSQL("INSERT INTO library VALUES ($keys, '$library', 1, NULL, NULL)")
        beginTransaction()
        try {
            val insert =
                compileStatement(
                    "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, seriesId, runtimeMs, " +
                        "createdAtMs, seenFromMs, firstSeenMs, providerIds) VALUES ('device-test-server', " +
                        "'device-test-user', ?, '$library', 'Episode', ?, ?, 1800000, 1600000000000, 2000, 1000, '')",
                )
            for (n in 0 until ITEMS) {
                insert.bindString(1, n.toString(16).padStart(32, '0'))
                insert.bindString(2, "Episode $n")
                insert.bindString(3, "series-${n % SERIES}")
                insert.executeInsert()
                insert.clearBindings()
            }
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { cursor ->
            val values = ArrayList<String>()
            while (cursor.moveToNext()) values += cursor.getString(0)
            values
        }

    @Test
    fun theMigratedSchemaMatchesElevenByName_andWritesNoRow() {
        helper.createDatabase(name, 10).apply {
            fill()
            close()
        }

        // runMigrationsAndValidate compares the migrated schema with 11.json, unknown tables and columns included.
        val migrated = helper.runMigrationsAndValidate(name, 11, true, Migration10To11())

        assertEquals(listOf("$ITEMS"), migrated.strings("SELECT COUNT(*) FROM item"))
        assertEquals(listOf("0"), migrated.strings("SELECT COUNT(*) FROM item_also_listed"))
        val fresh = "SELECT COUNT(*) FROM library WHERE walkedStamp IS NULL AND rewalkPending = 0"
        assertEquals(listOf("1"), migrated.strings("$fresh AND coveredFromMs IS NULL"))
        val itemIndexes = migrated.strings("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'item'")
        assertTrue(itemIndexes.toString(), "index_item_serverId_userId_seriesId" in itemIndexes)
        val alsoIndexes =
            migrated.strings("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'item_also_listed'")
        assertTrue(alsoIndexes.toString(), "index_item_also_listed_serverId_userId_libraryId" in alsoIndexes)
        assertEquals(listOf("$GENERATION"), migrated.strings("SELECT contentGeneration FROM sync_meta"))
        assertEquals(listOf("0"), migrated.strings("SELECT backfillPending FROM sync_meta"))
        migrated.close()
    }

    @Test
    @Suppress("InjectDispatcher") // A test: the open must run off the main thread, on a real pool.
    fun aLargeLibraryMigratesInUnderThreeSeconds_openedOffTheMainThread() {
        helper.createDatabase(name, 10).apply {
            fill()
            close()
        }

        runBlocking {
            val (tookMs, onMain) =
                withContext(Dispatchers.IO) {
                    val started = System.nanoTime()
                    val repository = RoomLibraryRepository.create(context, clock)
                    // The first query opens the file, and so migrates and checks it.
                    val generation = repository.contentGeneration(account)
                    val took = (System.nanoTime() - started) / NANOS_PER_MS
                    assertEquals(GENERATION, generation)
                    repository.close()
                    took to (Looper.myLooper() == Looper.getMainLooper())
                }
            assertFalse("the first open ran on the main thread", onMain)
            assertTrue("the migration took $tookMs ms, the limit is $LIMIT_MS", tookMs < LIMIT_MS)
        }
    }

    private companion object {
        const val ITEMS = 50_000
        const val SERIES = 500
        const val GENERATION = 4_242L
        const val LIMIT_MS = 3_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
