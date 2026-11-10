// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.database.SQLException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
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
 * #684 PR 4, on the API 25 emulator over the platform SQLiteOpenHelper that Fire OS 6 has: the JVM test
 * (`RuleSnapshotsIsolationTest`) runs the bundled driver. A walk of several selections is one transaction. A second
 * connection to the same file tries to write after the walk's items are read and before the series (one read per
 * selection) and the collections are; no selection of the walk sees any part of that write, and a walk taken after sees
 * all of it, so the test can tell. The write may be refused by the transaction's lock or kept out of the snapshot;
 * either way the snapshot is of one state. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class RuleSnapshotsIsolationOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("walk-test-server"), UserId("walk-test-user"))
    private val library = ItemId("33333333333333333333333333333331")
    private val show = ItemId("33333333333333333333333333333390")
    private val box = ItemId("33333333333333333333333333333400")
    private val member = ItemId("33333333333333333333333333333001")
    private val episodeMember = ItemId("33333333333333333333333333333002")
    private val created = Instant.fromEpochMilliseconds(1_600_000_000_000)
    private lateinit var reader: RoomLibraryRepository
    private lateinit var writer: RoomLibraryRepository

    @Before
    fun open() {
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
        reader = RoomLibraryRepository.create(context)
        writer = RoomLibraryRepository.create(context)
        runBlocking { reader.useAccount(account) }
    }

    @After
    fun close() {
        reader.close()
        writer.close()
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
    }

    private fun series(rating: String) = SeriesInfo(show, "Series", rating, listOf("x"), emptyList())

    private suspend fun seed() {
        reader.applySelection(account, setOf(library), nowMs = 0)
        val episode =
            MediaItem(
                id = ItemId("33333333333333333333333333333002"),
                name = "Episode",
                kind = ItemKind.Episode,
                runtime = 30.minutes,
                parentId = library,
                dateCreated = created,
                seriesId = show,
                seasonNumber = 1,
                episodeNumber = 1,
            )
        reader.upsertItems(account, library, stamp = null, rewalk = false, listOf(episode))
        reader.upsertSeries(account, library, stamp = null, rewalk = false, listOf(series("PG")))
        reader.writeCollection(account, box, "Box", listOf(CollectionMember(member, CollectionMemberKind.Movie)))
    }

    // What a sync that lands in the middle of a read does: edits the series, adds a member, and completes a full pass.
    private suspend fun theWriteInTheMiddle() {
        writer.upsertSeries(account, library, stamp = null, rewalk = false, listOf(series("R")))
        writer.writeCollection(
            account,
            box,
            "Box",
            listOf(
                CollectionMember(member, CollectionMemberKind.Movie),
                CollectionMember(episodeMember, CollectionMemberKind.Episode),
            ),
        )
        writer.recordCollectionPass(account, Clock.System.now(), relisted = true)
    }

    // The new selection, the current one, and an empty removed set: a walk of the shape the repair asks.
    private val walk = listOf(setOf(library), setOf(library), emptySet<ItemId>())

    @Test(timeout = 120_000)
    fun aWriteBetweenThePartsOfTheWalkIsSeenByNoSelection() =
        runBlocking {
            seed()
            var attempt: Result<Unit>? = null
            reader.faultDuringRuleSnapshot = { attempt = runCatching { runBlocking { theWriteInTheMiddle() } } }

            val snapshots = reader.ruleSnapshots(account, walk, farCutoff())

            assertTrue("the write was tried inside the walk", attempt != null)
            val failure = attempt?.exceptionOrNull()
            val refused = failure == null || generateSequence(failure) { it.cause }.any { it is SQLException }
            assertTrue("unexpected failure: $failure", refused)
            val box = CanonicalId.parse(box.value)
            val series = CanonicalId.parse(show.value)
            for ((i, snapshot) in snapshots.withIndex()) {
                if (i != 2) {
                    assertEquals("element $i: the series", "PG", snapshot.series.getValue(series).officialRating)
                }
                val collection = snapshot.collections.getValue(box)
                assertEquals(
                    "element $i: the movie member",
                    setOf(CanonicalId.parse(member.value)),
                    collection.movies,
                )
                assertEquals("element $i: no episode member", emptySet<Any>(), collection.episodes)
                assertFalse("element $i: the full pass is not seen", snapshot.collectionsSynced)
            }
        }

    @Test(timeout = 120_000)
    fun theNextWalkSeesTheWriteSoTheTestCouldTell() =
        runBlocking {
            seed()
            theWriteInTheMiddle()

            val snapshots = reader.ruleSnapshots(account, walk, farCutoff())

            assertEquals("R", snapshots[0].series.getValue(CanonicalId.parse(show.value)).officialRating)
            for (snapshot in snapshots) assertTrue(snapshot.collectionsSynced)
        }

    // A cut-off far enough ahead that anything the read could see would be in it.
    private fun farCutoff() = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + DAY_MS)

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
