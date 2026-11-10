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
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.time.Instant

/**
 * The contract the day build's caches stand on (#392, #480): `ruleSnapshot`'s output depends only on the account, the
 * libraries, the cut-off, and what `contentGeneration` reports; both caches (the in-memory one and the persisted days)
 * key on the generation alone, not on `collectionPassCompleted`. Today `collectionsSynced` comes from
 * `collection_sync.lastRelistMs`, and `recordCollectionPass` moves the generation when that is first set. If this test
 * fails, the output of `ruleSnapshot` has gained an input that the generation does not track: fix the signal, never
 * the test.
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

    private suspend fun state(cutoff: Instant = Instant.fromEpochMilliseconds(rig.clock.nowMs + FAR_MS)): State =
        State(
            repository.contentGeneration(ALICE),
            repository.collectionPassCompleted(ALICE).first(),
            repository.ruleSnapshot(ALICE, setOf(MOVIES), cutoff),
        )

    @Test
    fun aCompletedPassMovesTheSignalAtTheSameTimeAsCollectionsSynced() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
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
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            val states = mutableListOf(state())

            rig.serve(1, *rig.movies(10))
            rig.run()
            states += state()

            // A pass over what is stored moves the pass signal only; a plain read moves nothing.
            rig.clock.stepMs(HALF_HOUR_MS)
            rig.run()
            states += state()
            states += state()

            repository.upsertItems(ALICE, MOVIES, stamp = null, rewalk = false, listOf(SyncWorld.item("m", 0)))
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

    // #480: the days kept on disk are keyed on the generation and not on the pass, which every open's pass moves.
    @Test
    fun theGenerationAloneTellsSnapshotsApart() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            // One cut-off for every read, so only the library's state differs between them.
            val cutoff = Instant.fromEpochMilliseconds(rig.clock.nowMs + FAR_MS)
            val states = mutableListOf(state(cutoff))

            // The first full pass finds no BoxSet: no row is written, but collectionsSynced turns true.
            rig.run()
            states += state(cutoff)

            rig.clock.stepMs(HALF_HOUR_MS)
            rig.serve(1, *rig.movies(10))
            rig.run()
            states += state(cutoff)

            rig.clock.stepMs(HALF_HOUR_MS)
            rig.run()
            states += state(cutoff)

            rig.clock.stepMs(HALF_HOUR_MS)
            rig.run()
            states += state(cutoff)

            rig.clock.stepMs(HALF_HOUR_MS)
            rig.serve(1, *rig.movies(10, 11))
            rig.run()
            states += state(cutoff)

            for (a in states) {
                for (b in states) {
                    if (a.generation != b.generation) continue
                    assertEquals("same generation, same snapshot", a.snapshot, b.snapshot)
                }
            }
            assertEquals("the quiet passes moved only the pass", 4, states.map { it.generation }.distinct().size)
            assertEquals(6, states.map { it.pass }.distinct().size)
        }

    // #684: a walk's snapshots stand on the same key as `ruleSnapshot`'s. Under one generation every element of a walk
    // is the same, and is what a single read gives, so a caller that keeps one under the generation read before the walk
    // (the only key a walk's snapshot may be kept under) never serves a state the generation did not name.
    @Test
    fun theWalksSnapshotsStandOnTheSameGenerationKey() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            val cutoff = Instant.fromEpochMilliseconds(rig.clock.nowMs + FAR_MS)
            val walks = mutableListOf<Pair<Long, List<RuleSnapshot>>>()

            suspend fun walk() {
                val generation = repository.contentGeneration(ALICE)
                val walked = repository.ruleSnapshots(ALICE, listOf(setOf(MOVIES), emptySet(), setOf(MOVIES)), cutoff)
                val single = repository.ruleSnapshot(ALICE, setOf(MOVIES), cutoff)
                assertEquals("an element is a single read's", single, walked[0])
                assertEquals("a repeated selection is the same snapshot", walked[0], walked[2])
                assertEquals("the walk did not move the generation", generation, repository.contentGeneration(ALICE))
                walks += generation to walked
            }
            walk()
            rig.run()
            walk()
            rig.clock.stepMs(HALF_HOUR_MS)
            rig.serve(1, *rig.movies(10))
            rig.run()
            walk()
            rig.clock.stepMs(HALF_HOUR_MS)
            rig.run()
            walk()

            for ((generation, walked) in walks) {
                for ((other, again) in walks) {
                    if (generation == other) assertEquals("same generation, same walk", walked, again)
                }
            }
            assertTrue("the generation moved between walks", walks.map { it.first }.distinct().size >= 3)
        }

    @Test
    fun anotherChoiceOfLibrariesIsAnotherSnapshotUnderTheSameGenerationAndPass() =
        runBlocking {
            val shows = ItemId(hexId(0xB002))
            repository.applySelection(ALICE, setOf(MOVIES, shows), nowMs = 0)
            repository.upsertItems(ALICE, MOVIES, stamp = null, rewalk = false, listOf(SyncWorld.item("m", 0)))
            repository.upsertItems(ALICE, shows, stamp = null, rewalk = false, listOf(SyncWorld.item("s", 1)))
            val cutoff = Instant.fromEpochMilliseconds(rig.clock.nowMs + FAR_MS)

            val movies = repository.ruleSnapshot(ALICE, setOf(MOVIES), cutoff)
            val other = repository.ruleSnapshot(ALICE, setOf(shows), cutoff)

            assertNotEquals("the libraries are part of the key because they change the output", movies, other)
            assertNotEquals(movies.libraries, other.libraries)
        }

    private companion object {
        const val FAR_MS = 1_000L * 3_600_000L
        const val HALF_HOUR_MS = 30L * 60_000L
    }
}
