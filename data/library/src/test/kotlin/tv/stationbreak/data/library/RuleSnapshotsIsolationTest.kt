// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.database.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId

/**
 * #684 PR 4: the walk of several selections is one transaction and one state, whichever selection a part belongs to.
 * A second connection to the same file tries to write after the walk's items are read and before the series (one read
 * per selection) and the collections are: the seam [RoomLibraryRepository.faultDuringRuleSnapshot]. No selection of
 * the walk sees any part of that write, and a walk taken after sees all of it, so the test can tell. The same case runs
 * on a device (API 25) as `RuleSnapshotsIsolationOnDeviceTest`.
 */
class RuleSnapshotsIsolationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val reader by lazy { open() }
    private val writer by lazy { open() }
    private val show = ItemId(hexId(900))
    private val box = ItemId(hexId(1000))

    // Two repositories over one file: two connection pools, as a sync and a read are.
    private fun open() = TestLibraries.onDisk(folder.root, name = "walk.db", clock = clock, signedIn = listOf(ALICE))

    @After
    fun close() {
        reader.close()
        writer.close()
    }

    private suspend fun seed() {
        reader.applySelection(ALICE, setOf(RULE_LIBRARY), nowMs = 0)
        reader.put(ALICE, RULE_LIBRARY, episodeOf(1, 900))
        reader.putSeries(ALICE, RULE_LIBRARY, series(show.value, rating = "PG"))
        reader.writeCollection(ALICE, box, "Box", listOf(cm(hexId(1))))
        clock.stepMs(10_000)
    }

    // What a sync that lands in the middle of a read does: edits the series, adds a member, and completes a full pass.
    private suspend fun theWriteInTheMiddle() {
        clock.stepMs(10_000)
        writer.putSeries(ALICE, RULE_LIBRARY, series(show.value, rating = "R"))
        writer.writeCollection(ALICE, box, "Box", listOf(cm(hexId(1)), cm(hexId(2), CollectionMemberKind.Episode)))
        writer.recordCollectionPass(ALICE, at(clock.nowMs), relisted = true)
    }

    // The new selection, the current one, and the removed libraries: a walk of the shape the repair asks.
    private val walk = listOf(setOf(RULE_LIBRARY), setOf(RULE_LIBRARY), emptySet<ItemId>())

    @Test(timeout = 120_000)
    fun aWriteBetweenThePartsOfTheWalkIsSeenByNoSelection() =
        runBlocking {
            seed()
            var attempt: Result<Unit>? = null
            reader.faultDuringRuleSnapshot = { attempt = runCatching { runBlocking { theWriteInTheMiddle() } } }
            val cutoff = at(clock.nowMs + 86_400_000L)

            val snapshots = reader.ruleSnapshots(ALICE, walk, cutoff)

            // The walk holds the database's writer lock, so the write is refused (database locked) and nothing of it
            // lands; a walk in several transactions would let it in, and the assertions below would then fail.
            assertTrue("the write was tried inside the walk", attempt != null)
            val failure = attempt?.exceptionOrNull()
            val refused = failure == null || generateSequence(failure) { it.cause }.any { it is SQLException }
            assertTrue("unexpected failure: $failure", refused)
            println("RuleSnapshotsIsolationTest: the write inside the walk ${failure ?: "went through"}")
            for ((i, snapshot) in snapshots.withIndex()) {
                if (i == 2) {
                    assertEquals("the empty selection has no series", emptyMap<Any, Any>(), snapshot.series)
                } else {
                    assertEquals("element $i: the series", "PG", snapshot.series.getValue(canon(900)).officialRating)
                }
                val collection = snapshot.collections.getValue(canon(1000))
                assertEquals("element $i: the movie member", setOf(canon(1)), collection.movies)
                assertEquals("element $i: no episode member", emptySet<Any>(), collection.episodes)
                assertFalse("element $i: the full pass is not seen", snapshot.collectionsSynced)
            }
        }

    @Test(timeout = 120_000)
    fun theNextWalkSeesTheWriteSoTheTestCouldTell() =
        runBlocking {
            seed()
            theWriteInTheMiddle()

            val snapshots = reader.ruleSnapshots(ALICE, walk, at(clock.nowMs + 86_400_000L))

            for ((i, snapshot) in snapshots.withIndex()) {
                assertEquals("element $i", setOf(canon(2)), snapshot.collections.getValue(canon(1000)).episodes)
                assertTrue("element $i", snapshot.collectionsSynced)
            }
            assertEquals("R", snapshots[0].series.getValue(canon(900)).officialRating)
        }
}
