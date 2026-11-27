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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * ADR-0029: version 12 to 13 on the platform SQLite of the API 25 emulator. Room's `MigrationTestHelper` makes the file
 * from the committed `12.json`, runs [Migration12To13] and checks the result against the committed `13.json`. The
 * existing series and version rows survive and the new `series_version.providerIds` column is empty.
 * The first write after the update, an id change on a series whose migrated version has no ids, reads back per cut-off
 * through the app's own repository. `Migration12To13Test` runs the rest on the JVM. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13OnDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = RoomLibraryRepository.DATABASE_NAME
    private val keys = "'device-test-server', 'device-test-user'"
    private val library = "3333333333333333333333333333333a"

    // Canonical (32 hex digits), as the as-of read needs of a series ID.
    private val show = "33333333333333333333333333333390"
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun remove() {
        context.deleteDatabase(name)
    }

    private fun SupportSQLiteDatabase.fill() {
        execSQL("INSERT INTO active_account VALUES ($keys)")
        execSQL("INSERT INTO account_state VALUES ($keys, 1, NULL, NULL, 1)")
        execSQL("INSERT INTO sync_meta VALUES ($keys, $GENERATION, 0, 0, 0)")
        execSQL("INSERT INTO library (serverId, userId, libraryId, fetched) VALUES ($keys, '$library', 1)")
        execSQL(
            "INSERT INTO series (serverId, userId, seriesId, libraryId, name, officialRating, genres, tags, " +
                "deletedAtMs, seenFromMs, firstSeenMs, studios, providerIds) VALUES ($keys, '$show', '$library', " +
                "'A Show', 'TV-Y7', '', '', NULL, 3000, 1000, '', 'tvdb=1')",
        )
        execSQL(
            "INSERT INTO series_version (serverId, userId, seriesId, seenFromMs, supersededAtMs, libraryId, " +
                "officialRating, genres, tags, deletedAtMs, studios, name) VALUES ($keys, '$show', 1000, 3000, " +
                "'$library', 'TV-Y', '', '', NULL, '', 'A Show')",
        )
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> =
        query(sql).use { cursor ->
            val values = ArrayList<String?>()
            while (cursor.moveToNext()) values += if (cursor.isNull(0)) null else cursor.getString(0)
            values
        }

    @Test
    fun theMigratedSchemaMatchesThirteen_theRowsSurvive_andTheVersionHasNoIds() {
        helper.createDatabase(name, 12).apply {
            fill()
            close()
        }

        // runMigrationsAndValidate compares the migrated schema with 13.json, unknown tables and columns included.
        val migrated = helper.runMigrationsAndValidate(name, 13, true, Migration12To13())

        assertEquals(listOf<String?>(""), migrated.strings("SELECT providerIds FROM series_version"))
        assertEquals(listOf<String?>("TV-Y"), migrated.strings("SELECT officialRating FROM series_version"))
        assertEquals(listOf<String?>("tvdb=1"), migrated.strings("SELECT providerIds FROM series"))
        assertEquals(listOf<String?>("$GENERATION"), migrated.strings("SELECT contentGeneration FROM sync_meta"))
        migrated.close()
    }

    // The first write after the update: the series' ids change, so the migrated empty-ids version is followed by one
    // holding the ids the row had. Each cut-off reads the ids of the interval it falls in (the JVM twin does the same).
    @Test
    fun anIdChangeAfterTheMigrationKeepsTheIntervalsContiguousAndEachCutoffReadsItsOwnIds() {
        helper.createDatabase(name, 12).apply {
            fill()
            close()
        }
        val clock = SteppedClock()
        // The app's builder opens the version 12 file and runs the migration on the first query.
        val repository = RoomLibraryRepository.create(context, clock)
        try {
            runBlocking {
                val episode =
                    MediaItem(
                        id = ItemId("33333333333333333333333333333001"),
                        name = "An Episode",
                        kind = ItemKind.Episode,
                        runtime = 30.minutes,
                        parentId = ItemId(library),
                        dateCreated = Instant.fromEpochMilliseconds(clock.nowMs - 1_440 * 60_000L),
                        seriesId = ItemId(show),
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val items = repository.upsertItems(account, ItemId(library), null, false, listOf(episode))
                assertTrue("the library row refused the write: $items", items is PageResult.Written)
                clock.stepMs(10_000)
                val info =
                    SeriesInfo(
                        ItemId(show),
                        "Series $show",
                        "TV-Y7",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        mapOf("tvdb" to "2"),
                    )
                val written = repository.upsertSeries(account, ItemId(library), null, false, listOf(info))
                assertTrue("the library row refused the write: $written", written is PageResult.Written)
                clock.stepMs(10_000)

                val versions = repository.seriesVersions(account).sortedBy { it.seenFromMs }
                val current = repository.currentSeries(account).single()
                assertEquals(listOf("", "tvdb=1"), versions.map { it.providerIds })
                assertEquals(current.firstSeenMs, versions.first().seenFromMs)
                assertEquals(versions[0].supersededAtMs, versions[1].seenFromMs)
                assertEquals(current.seenFromMs, versions.last().supersededAtMs)

                suspend fun idsAt(cutoffMs: Long) =
                    repository
                        .ruleSnapshot(account, setOf(ItemId(library)), Instant.fromEpochMilliseconds(cutoffMs))
                        .series
                        .getValue(CanonicalId.parse(show))
                        .providerIds
                assertEquals(emptyMap<String, String>(), idsAt(2_000))
                assertEquals(mapOf("tvdb" to "1"), idsAt(4_000))
                assertEquals(mapOf("tvdb" to "2"), idsAt(clock.nowMs))
            }
        } finally {
            repository.close()
        }
    }

    /** A clock the test moves by hand, as the JVM twin's `FakeClock` does (that one is not on this classpath). */
    private class SteppedClock : Clock {
        var nowMs = 1_700_000_000_000L

        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)

        fun stepMs(ms: Long) {
            nowMs += ms
        }
    }

    private companion object {
        const val GENERATION = 4_242L
    }
}
