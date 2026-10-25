// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.time.Instant

/**
 * The contract the day build's snapshot cache stands on (#392): `ruleSnapshot`'s output depends only on the account,
 * the libraries, the cut-off, and what `contentGeneration` and `collectionPassCompleted` report. Today
 * `collectionsSynced` comes from `collection_sync.lastRelistMs` and the pass signal from its `collectionsSinceMs`;
 * they agree because `recordCollectionPass` writes both in one row. If this test fails, either the output of
 * `ruleSnapshot` has gained an input that neither number tracks, or the two have come apart: fix the signal, never the
 * test.
 */
class RuleSnapshotKeyContractTest {
    private val rig = CollectionRig()
    private val repository get() = rig.repository

    @After
    fun close() = rig.repository.close()

    private data class State(
        val generation: Long,
        val pass: Instant?,
        val snapshot: RuleSnapshot,
    )

    private suspend fun state(): State {
        val cutoff = Instant.fromEpochMilliseconds(rig.clock.nowMs + FAR_MS)
        return State(
            repository.contentGeneration(ALICE),
            repository.collectionPassCompleted(ALICE).first(),
            repository.ruleSnapshot(ALICE, setOf(MOVIES), cutoff),
        )
    }

    @Test
    fun aCompletedPassMovesTheSignalAtTheSameTimeAsCollectionsSynced() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val before = state()
            assertFalse(before.snapshot.collectionsSynced)
            assertNull(before.pass)

            rig.serve(1, *rig.movies(10))
            rig.run()
            val after = state()

            assertTrue("a full pass has succeeded", after.snapshot.collectionsSynced)
            assertNotNull(after.pass)
            assertNotEquals("the pass signal moved with it", before.pass, after.pass)
        }

    @Test
    fun whenNeitherNumberMovesTheSnapshotIsTheSame() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val states = mutableListOf(state())

            rig.serve(1, *rig.movies(10))
            rig.run()
            states += state()

            // A pass over what is stored moves the pass signal only; a plain read moves nothing.
            rig.clock.stepMs(HALF_HOUR_MS)
            rig.run()
            states += state()
            states += state()

            repository.upsertItems(ALICE, MOVIES, listOf(SyncWorld.item("m", 0)))
            states += state()

            rig.clock.stepMs(HALF_HOUR_MS)
            rig.serve(1, *rig.movies(10, 11))
            rig.run()
            states += state()

            for (a in states) {
                for (b in states) {
                    if (a.generation == b.generation && a.pass == b.pass) {
                        assertEquals("same generation and pass, same snapshot", a.snapshot, b.snapshot)
                    }
                }
            }
            assertTrue("the states were told apart", states.map { it.generation to it.pass }.distinct().size >= 4)
        }

    private companion object {
        const val FAR_MS = 1_000L * 3_600_000L
        const val HALF_HOUR_MS = 30L * 60_000L
    }
}
