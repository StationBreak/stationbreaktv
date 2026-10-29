// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * #480: the content generation moves in the transaction of every write that changes what a rule snapshot holds, and
 * in no other. Each kind of change moves it (an item or series new, edited, deleted or back; a collection new, deleted
 * or back; a member added or removed; another choice of libraries; the first full collection pass, which turns
 * `collectionsSynced` true). A call that finds every row already as it would leave it moves nothing, whichever write
 * it is.
 */
class GenerationOnlyOnChangeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val film = ItemId(hexId(1))
    private val other = ItemId(hexId(2))
    private val show = ItemId(hexId(500))
    private val box = ItemId(hexId(0x1001))
    private val none = emptyList<ItemId>()
    private val stored = listOf(cm(film.value), cm(show.value, CollectionMemberKind.Series))

    @After
    fun close() = repository.close()

    private suspend fun world() {
        repository.select(ALICE, setOf(MOVIES, SHOWS))
        repository.put(ALICE, MOVIES, sitem(1), sitem(2))
        repository.putSeries(ALICE, SHOWS, series(show.value))
        repository.writeCollection(ALICE, box, "Box", stored)
        clock.stepMs(10_000)
    }

    /** Runs [write] and checks that it moved the generation, or with [moves] false that it did not. */
    private suspend fun check(
        what: String,
        moves: Boolean = true,
        write: suspend () -> Unit,
    ) {
        val before = repository.contentGeneration(ALICE)
        write()
        clock.stepMs(1_000)
        assertEquals(what, moves, before != repository.contentGeneration(ALICE))
    }

    /** [check] for a write that must leave the generation where it was. */
    private suspend fun still(
        what: String,
        write: suspend () -> Unit,
    ) = check(what, moves = false, write)

    @Test
    fun everyKindOfChangeMovesIt() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            check("an item that is new") { repository.put(ALICE, MOVIES, sitem(1)) }
            check("an item that is edited") { repository.put(ALICE, MOVIES, sitem(1).copy(name = "Renamed")) }
            check("an item that is deleted") { repository.tombstone(ALICE, listOf(film)) }
            check("an item that is back") { repository.revive(ALICE, listOf(film)) }
            check("a series that is new") { repository.putSeries(ALICE, MOVIES, series(show.value)) }
            check("a series that is deleted") { repository.tombstoneSeries(ALICE, listOf(show)) }
            check("a series that is back") { repository.reviveSeries(ALICE, listOf(show)) }
            check("a collection that is new") { repository.writeCollection(ALICE, box, "Box", listOf(cm(film.value))) }
            check("a member added") {
                repository.writeCollection(ALICE, box, "Box", listOf(cm(film.value), cm(other.value)))
            }
            check("a member removed") { repository.writeCollection(ALICE, box, "Box", listOf(cm(other.value))) }
            check("a collection that is deleted") { repository.tombstoneCollections(ALICE, listOf(box)) }
            check("a collection that is back") {
                repository.writeCollection(ALICE, box, "Box", listOf(cm(other.value)))
            }
            check("another choice of libraries") { repository.select(ALICE, setOf(MOVIES, SHOWS)) }
        }

    @Test
    fun theReconcilesWriteMovesItForEachKindOfDecision() =
        runBlocking {
            world()
            check("an item gone") { repository.applyReconcile(ALICE, listOf(film), none, none, none) }
            check("an item back") { repository.applyReconcile(ALICE, none, listOf(film), none, none) }
            check("a series gone") { repository.applyReconcile(ALICE, none, none, listOf(show), none) }
            check("a series back") { repository.applyReconcile(ALICE, none, none, none, listOf(show)) }
        }

    @Test
    fun aCallThatFindsEveryRowAsItWouldLeaveItMovesNothing() =
        runBlocking {
            world()
            repository.tombstone(ALICE, listOf(other))
            clock.stepMs(1_000)

            still("the same page again") { repository.put(ALICE, MOVIES, sitem(1)) }
            still("the same series again") { repository.putSeries(ALICE, SHOWS, series(show.value)) }
            still("the same choice of libraries") { repository.select(ALICE, setOf(SHOWS, MOVIES)) }
            still("the same collection again") { repository.writeCollection(ALICE, box, "Box", stored) }
            still("an item already deleted") { repository.tombstone(ALICE, listOf(other)) }
            still("an item that is live") { repository.revive(ALICE, listOf(film)) }
            still("an item never held") { repository.tombstone(ALICE, listOf(ItemId(hexId(9)))) }
            still("a series that is live") { repository.reviveSeries(ALICE, listOf(show)) }
            still("a reconcile whose decisions are all in place already") {
                repository.applyReconcile(ALICE, listOf(other), listOf(film), none, listOf(show))
            }
            repository.tombstoneSeries(ALICE, listOf(show))
            still("a series already deleted") { repository.tombstoneSeries(ALICE, listOf(show)) }
            repository.tombstoneCollections(ALICE, listOf(box))
            still("a collection already deleted") { repository.tombstoneCollections(ALICE, listOf(box)) }
        }

    @Test
    fun deletingAnItemAlreadyDeletedStillMovesItWhenACollectionHadTakenItBackIn() =
        runBlocking {
            world()
            repository.tombstone(ALICE, listOf(film))
            clock.stepMs(1_000)
            // The BoxSet still lists the film, so the next collection pass makes it a member again.
            repository.writeCollection(ALICE, box, "Box", stored)
            clock.stepMs(1_000)

            check("the member row it ends is a change") {
                repository.applyReconcile(ALICE, listOf(film), none, none, none)
            }
        }

    @Test
    fun theFirstFullCollectionPassMovesItAndLaterPassesDoNot() =
        runBlocking {
            world()
            val now = { Instant.fromEpochMilliseconds(clock.nowMs) }

            still("a pass that did not re-list everything leaves collectionsSynced false") {
                repository.recordCollectionPass(ALICE, now(), relisted = false)
            }
            check("the first full pass turns collectionsSynced true") {
                repository.recordCollectionPass(ALICE, now(), relisted = true)
            }
            still("a later full pass") { repository.recordCollectionPass(ALICE, now(), relisted = true) }
            still("a later incremental pass") { repository.recordCollectionPass(ALICE, now(), relisted = false) }
        }
}
