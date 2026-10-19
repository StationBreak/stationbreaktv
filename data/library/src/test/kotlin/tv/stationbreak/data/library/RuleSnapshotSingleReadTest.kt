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
 * Spec 10 task 3.3.3 (Granny H1, M1): `ruleSnapshot` is one transaction and one state. A second connection to the same
 * file tries to write in the middle of the read, after the items are read and before the series and the collections are
 * (the test seam inside the transaction). The snapshot does not see that write, and a snapshot taken after does, so
 * the test can tell. The write may be refused by the transaction's lock or kept out of the snapshot; either way, the
 * snapshot is of one state. The test can fail: with `@Transaction` removed from `CacheTransactionDao.ruleSnapshot` for
 * one run, the write went through and the snapshot held the new rating (expected PG, was R). The channel
 * path's "no second `scheduleItems` call" check is not here: it moved to spec 12 (#275).
 */
class RuleSnapshotSingleReadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val reader by lazy { open() }
    private val writer by lazy { open() }
    private val show = ItemId(hexId(900))
    private val box = ItemId(hexId(1000))

    // Two repositories over one file: two connection pools, as a sync and a read are.
    private fun open() = TestLibraries.onDisk(folder.root, name = "single.db", clock = clock, signedIn = listOf(ALICE))

    @After
    fun close() {
        reader.close()
        writer.close()
    }

    private suspend fun seed() {
        reader.select(ALICE, setOf(RULE_LIBRARY))
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

    @Test(timeout = 120_000)
    fun aWriteBetweenThePartsOfTheReadIsNotSeen() =
        runBlocking {
            seed()
            var attempt: Result<Unit>? = null
            reader.faultDuringRuleSnapshot = { attempt = runCatching { runBlocking { theWriteInTheMiddle() } } }
            // A cut-off far enough ahead that anything the read could see would be in it.
            val cutoff = at(clock.nowMs + 86_400_000L)

            val snapshot = reader.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), cutoff)

            // The read holds the database's writer lock, so the write is refused (database locked) and nothing of it
            // lands; a read in several transactions would let it in, and the assertions below would then fail.
            assertTrue("the write was tried inside the read", attempt != null)
            val failure = attempt?.exceptionOrNull()
            // Nothing but a refusal by the database's lock is acceptable: any other failure is a bug in the test.
            assertTrue("unexpected failure: $failure", failure == null || generateSequence(failure) { it.cause }.any { it is SQLException })
            val outcome = failure ?: "went through"
            println("RuleSnapshotSingleReadTest: the write inside the read $outcome")
            assertEquals("PG", snapshot.series.getValue(canon(900)).officialRating)
            assertEquals(setOf(canon(1)), snapshot.collections.getValue(canon(1000)).movies)
            assertEquals(emptySet<Any>(), snapshot.collections.getValue(canon(1000)).episodes)
            assertFalse("the full pass is not seen", snapshot.collectionsSynced)
        }

    @Test(timeout = 120_000)
    fun theNextSnapshotSeesTheWriteSoTheTestCouldTell() =
        runBlocking {
            seed()
            theWriteInTheMiddle()

            val snapshot = reader.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(clock.nowMs + 86_400_000L))

            assertEquals("R", snapshot.series.getValue(canon(900)).officialRating)
            assertEquals(setOf(canon(2)), snapshot.collections.getValue(canon(1000)).episodes)
            assertTrue(snapshot.collectionsSynced)
        }
}
