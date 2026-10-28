// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Spec 18 section 2 (Review Focus 4): the cache's first real migration. A file the version 7 app left, made from the
 * committed `7.json`, is opened by the current code through the same builder the app uses. Room runs [Migration7To8]
 * and then checks the result against the schema it was compiled with (the one exported as `8.json`), so a migration
 * that differs from the entities fails here. What the first launch after the upgrade needs survives: the signed-in
 * account, the items and their history, the time of the last success. Every account is flagged for the studios
 * re-read, and the cache answers with no server.
 *
 * Room's `MigrationTestHelper` needs an Android `AssetManager` to read the schema files, which the JVM tests do not
 * have (Robolectric is not in the build), so it runs on the emulator in `Migration7To8OnDeviceTest`, over the platform
 * SQLite the app uses. Invented names only.
 */
class Migration7To8Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val alice = ALICE.server.value to ALICE.user.value
    private val bob = BOB.server.value to BOB.user.value
    private val lastSuccess = Instant.fromEpochMilliseconds(EPOCH.toEpochMilliseconds() - 2.hours.inWholeMilliseconds)
    private val bobsSuccess = EPOCH.toEpochMilliseconds() - 5.hours.inWholeMilliseconds

    private fun SQLiteConnection.insert(
        table: String,
        values: String,
    ) = execSQL("INSERT INTO $table VALUES ($values)")

    // A version 7 file as the app left it: Alice signed in with two items, one edited, and one series; Bob's account
    // state left behind with no sync_meta row (the row is created only by a write that bumps the generation).
    private fun writeVersion7(file: File) =
        SchemaFile.create(file, 7) { db ->
            val (server, user) = alice
            val account = "'$server', '$user'"
            val movies = "'${MOVIES.value}'"
            db.insert("active_account", account)
            db.insert("account_state", "$account, 1, NULL, NULL, ${lastSuccess.toEpochMilliseconds()}")
            db.insert("account_state", "'${bob.first}', '${bob.second}', 1, NULL, NULL, $bobsSuccess")
            db.insert("sync_meta", "$account, $GENERATION, 2, 0")
            db.insert("library", "$account, $movies, 1")
            val created = EPOCH.toEpochMilliseconds() - 60_000
            for ((n, seen) in listOf(1 to SEEN_EDITED, 2 to SEEN_FIRST)) {
                val id = "'${hexId(n)}'"
                db.execSQL(
                    "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, runtimeMs, createdAtMs, " +
                        "officialRating, seenFromMs, firstSeenMs) " +
                        "VALUES ($account, $id, $movies, 'Movie', 'Invented $n', 5400000, $created, 'PG', $seen, " +
                        "$SEEN_FIRST)",
                )
                db.insert("item_genre", "$account, $id, 0, 'Drama'")
            }
            db.execSQL(
                "INSERT INTO item_version (serverId, userId, itemId, seenFromMs, supersededAtMs, libraryId, kind, " +
                    "runtimeMs, createdAtMs, officialRating, genres, tags) " +
                    "VALUES ($account, '${hexId(1)}', $SEEN_FIRST, $SEEN_EDITED, $movies, 'Movie', 5400000, " +
                    "$created, 'G', '5:Drama', '')",
            )
            db.insert("series", "$account, 'invented-series', $movies, 'Invented Series', 'TV-Y', '', '', NULL, 1, 1")
            db.insert("series_version", "$account, 'invented-series', 0, 1, $movies, 'TV-G', '', '', NULL")
        }

    // The file opened by the current code, with nobody signed in by the test: only what the file holds.
    private fun migrated(file: File): RoomLibraryRepository {
        val none = emptyList<LibraryAccount>()
        return TestLibraries.onDisk(folder.root, name = file.name, clock = clock, signedIn = none)
    }

    @Test
    fun aVersion7FileKeepsItsAccountItemsHistoryAndLastSuccess() =
        runBlocking {
            val file = File(folder.root, "upgrade.db")
            writeVersion7(file)

            val repository = migrated(file)

            assertEquals(listOf(ActiveAccountEntity(alice.first, alice.second)), repository.dao.activeAccounts())
            val items = repository.stored(ALICE)
            assertEquals(listOf(hexId(1), hexId(2)), items.map { it.item.id.value })
            assertTrue(items.all { it.item.genres == listOf("Drama") && it.item.studios.isEmpty() })
            val version = repository.versions(ALICE).single()
            assertEquals("G", version.officialRating)
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertEquals("", version.studios)
            assertEquals("", repository.currentSeries(ALICE).single().studios)
            assertEquals("", repository.seriesVersions(ALICE).single().studios)
            assertEquals(LibraryStatus(SyncState.Idle, lastSuccess), repository.status(ALICE).first())
            assertEquals(bobsSuccess, repository.dao.state(bob.first, bob.second)?.lastSuccessMs)
            repository.close()
        }

    @Test
    fun everyAccountIsFlaggedForTheStudiosReReadAndAMissingMetaRowIsSeeded() =
        runBlocking {
            val file = File(folder.root, "flags.db")
            writeVersion7(file)

            val repository = migrated(file)

            val alices = repository.dao.metaRow(alice.first, alice.second)!!
            assertTrue(alices.backfillPending)
            assertEquals(GENERATION, alices.contentGeneration)
            assertEquals(2, alices.failedPasses)
            // Bob had no row: one is made, seeded as generationSeed seeds one, so the flag is not lost to a later
            // row created with it off.
            val bobs = repository.dao.metaRow(bob.first, bob.second)!!
            assertTrue(bobs.backfillPending)
            assertEquals(clock.nowMs shl LibraryDao.GENERATION_SHIFT, bobs.contentGeneration)
            assertEquals(0, bobs.failedPasses)
            assertFalse(bobs.passRunning)
            repository.close()
        }

    @Test
    fun theMigratedFileAnswersWithNoServer() =
        runBlocking {
            // Review Focus 4: the first launch after the upgrade, offline. The repository has no server at all; the
            // channels' read (the rule snapshot) and the read of OfflineReadTest come from the migrated file alone.
            val file = File(folder.root, "offline.db")
            writeVersion7(file)
            val upgraded = migrated(file)
            upgraded.dao.activeAccounts() // Room opens the file, and so migrates it, on its first query.
            upgraded.close()
            clock.instant += 30.hours

            val reopened = migrated(file)

            assertEquals(listOf(hexId(1), hexId(2)), reopened.snapshot(ALICE, setOf(MOVIES), EPOCH).map { it.id.value })
            assertEquals(LibraryStatus(SyncState.Idle, lastSuccess), reopened.status(ALICE).first())
            val rules = reopened.ruleSnapshot(ALICE, setOf(MOVIES), clock.instant)
            assertEquals(listOf(hexId(1), hexId(2)), rules.items.map { it.item.id.value })
            assertTrue(rules.items.all { it.studios.isEmpty() })
            reopened.close()
        }

    // A version 7 file with one movie and one episode of one series, all first seen long before EPOCH.
    private fun writeVersion7WithASeries(file: File) =
        SchemaFile.create(file, 7) { db ->
            val (server, user) = alice
            val account = "'$server', '$user'"
            val movies = "'${MOVIES.value}'"
            db.insert("active_account", account)
            db.insert("account_state", "$account, 1, NULL, NULL, ${lastSuccess.toEpochMilliseconds()}")
            db.insert("library", "$account, $movies, 1")
            for ((n, kind) in listOf(1 to "Movie", 3 to "Episode")) {
                val seriesId = if (kind == "Episode") "'$SERIES'" else "NULL"
                db.execSQL(
                    "INSERT INTO item (serverId, userId, itemId, libraryId, kind, name, seriesId, runtimeMs, " +
                        "createdAtMs, seenFromMs, firstSeenMs) VALUES ($account, '${hexId(n)}', $movies, '$kind', " +
                        "'Name of $n', $seriesId, 5400000, ${CREATED.toEpochMilliseconds()}, $SEEN_FIRST, " +
                        "$SEEN_FIRST)",
                )
            }
            db.insert("series", "$account, '$SERIES', $movies, 'Invented Series', NULL, '', '', NULL, 1000, 1000")
        }

    @Test
    fun theBackfillOnAMigratedFileFillsStudiosOnlyFromItsOwnMomentOn() =
        runBlocking {
            // Review item L2: the whole path. An as-of read before the re-read keeps "no studios", for items and
            // series; one after it reads them.
            val file = File(folder.root, "backfill.db")
            writeVersion7WithASeries(file)
            val repository = migrated(file)
            val movie = sitem(1).copy(dateCreated = CREATED, studios = listOf("Invented Pictures"))
            val episode =
                sitem(3).copy(
                    kind = ItemKind.Episode,
                    dateCreated = CREATED,
                    seriesId = ItemId(SERIES),
                )
            val source = ListLibrarySource(mutableListOf(movie, episode))
            val server = PagedMediaServer(listOf(SyncWorld.movies), mapOf(MOVIES to source))
            server.seriesSources[MOVIES] =
                SeriesSource(mutableListOf(series(SERIES, name = "Invented Series", studios = listOf("Invented Kids"))))
            clock.instant += 1.hours
            val reread = clock.instant

            val result = StayCurrentEngine(repository, server, clock).backfill(SyncWorld.session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertFalse(repository.backfillPending(ALICE))
            val before = repository.ruleSnapshot(ALICE, setOf(MOVIES), reread - 1.hours)
            assertEquals(listOf(hexId(1), hexId(3)), before.items.map { it.item.id.value })
            assertTrue(before.items.all { it.studios.isEmpty() })
            assertEquals(
                emptyList<String>(),
                before.series.values
                    .single()
                    .studios,
            )
            clock.instant += 1.hours
            val after = repository.ruleSnapshot(ALICE, setOf(MOVIES), clock.instant)
            assertEquals(listOf(listOf("Invented Pictures"), emptyList()), after.items.map { it.studios })
            assertEquals(
                listOf("Invented Kids"),
                after.series.values
                    .single()
                    .studios,
            )
            repository.close()
        }

    @Test
    fun aNewInstallCreatesVersion8WithTheFlagOff() =
        runBlocking {
            // A brand-new install is created at version 8 with no migration: its first sync reads studios anyway.
            val repository = TestLibraries.onDisk(folder.root, name = "new.db", clock = clock)
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))

            assertFalse(repository.dao.metaRow(alice.first, alice.second)!!.backfillPending)
            repository.close()
        }

    private companion object {
        const val GENERATION = 1_234_567L
        const val SEEN_FIRST = 1_000L
        const val SEEN_EDITED = 2_000L
        val SERIES = hexId(0x5E)
        val CREATED = EPOCH - 30.hours
    }
}
