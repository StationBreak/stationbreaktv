// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 06 task 4.2: the incremental pass fetches only what changed since the last pass began (less a 10 minute
 * margin), upserts it, and records the pass's own start as the new moment, only when it succeeds. Jellyfin never sends
 * a per-item save time, so the moment comes from our clock and never from the items (spec, Technical Considerations).
 */
class IncrementalSyncTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val library = SyncWorld.source("m", 300)
    private val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to library))

    @After
    fun close() = repository.close()

    /** The first sync, finished at [EPOCH]: the recorded moment every test starts from. */
    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
        assertEquals(SyncOutcome.Completed, outcome)
        server.calls.clear()
    }

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun lastSuccess() = repository.activeStatus().first()?.lastSuccess

    private fun newItem(number: Int): MediaItem = SyncWorld.item("new", number).copy(dateCreated = clock.now())

    private fun rename(
        index: Int,
        name: String,
    ): MediaItem {
        val renamed = library.items[index].copy(name = name)
        library.items[index] = renamed
        return renamed
    }

    private suspend fun storedNames() = repository.stored(ALICE).associate { it.item.id.value to it.item.name }

    @Test
    fun aNewAndAChangedItemAreStored() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            library.items += newItem(1)
            val renamed = rename(7, "Renamed on the server")
            library.saved[renamed.id] = clock.now() - 1.minutes

            val engine = engine()
            val result = engine.incremental(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, result.outcome)
            val names = storedNames()
            assertEquals(301, names.size)
            assertEquals("Renamed on the server", names["m-00007"])
            assertEquals("Name of new 1", names["new-00001"])
            assertEquals(PassSummary(inserted = 1, updated = 1, unchanged = 0), result.summary)
        }

    @Test
    fun anItemThatIsUnchangedIsNotRewrittenAndAnOldOneIsNotEvenAsked() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            // Saved five minutes before the last pass began: inside the margin, so it comes back, identical.
            library.saved[library.items[3].id] = EPOCH - 5.minutes

            val result = engine().incremental(session, ALICE, setOf(MOVIES))

            assertEquals(PassSummary(inserted = 0, updated = 0, unchanged = 1), result.summary)
            val asked = server.itemCalls.single { it.startIndex == 0 }
            assertEquals(EPOCH - 10.minutes, asked.changedSince)
        }

    @Test
    fun theMarginCatchesAnItemSavedJustBeforeTheRecordedMomentButNotOneWellBeforeIt() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            val justBefore = rename(10, "Saved nine minutes before")
            library.saved[justBefore.id] = EPOCH - 9.minutes
            val wellBefore = rename(20, "Saved eleven minutes before")
            library.saved[wellBefore.id] = EPOCH - 11.minutes

            engine().incremental(session, ALICE, setOf(MOVIES))

            val names = storedNames()
            assertEquals("Saved nine minutes before", names["m-00010"])
            assertEquals("Name of m 20", names["m-00020"])
        }

    @Test
    fun theRecordedMomentIsWhenThePassStartedNotWhenItEnded() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            val started = clock.now()
            server.respond = {
                clock.instant += 3.minutes
                null
            }

            engine().incremental(session, ALICE, setOf(MOVIES))

            assertEquals(started, lastSuccess())
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
        }

    @Test
    fun theMomentIsNeverTakenFromTheItems() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            // An item "from the future" must not move the moment: items carry no save time we could trust.
            library.items += newItem(1).copy(dateCreated = clock.now() + 5.hours)
            val started = clock.now()

            engine().incremental(session, ALICE, setOf(MOVIES))

            assertEquals(started, lastSuccess())
        }

    @Test
    fun aFailedPassKeepsTheOldMomentAndRecordsTheFailure() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            library.items += newItem(1)
            server.respond = { MediaServerError.Timeout() }

            val outcome = engine().incremental(session, ALICE, setOf(MOVIES)).outcome

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            val status = repository.activeStatus().first()!!
            assertEquals(EPOCH, status.lastSuccess)
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Network), status.state)
            assertEquals(300, storedNames().size)
        }

    @Test
    fun aPassThatDiesHalfWayLeavesTheMomentAloneSoTheNextPassCoversTheSameWindow() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            (0 until 250).forEach { library.saved[library.items[it].id] = clock.now() - 1.minutes }
            var asked = 0
            server.respond = { if (asked++ == 1) MediaServerError.Timeout() else null }

            engine().incremental(session, ALICE, setOf(MOVIES))
            assertEquals(EPOCH, lastSuccess())
            server.respond = { null }
            server.calls.clear()
            engine().incremental(session, ALICE, setOf(MOVIES))

            assertEquals(EPOCH - 10.minutes, server.itemCalls.first().changedSince)
            assertEquals(clock.now(), lastSuccess())
        }

    @Test
    fun withoutAFinishedFirstSyncThereIsNothingToTopUp() =
        runBlocking {
            repository.useAccount(ALICE)

            val outcome = engine().incremental(session, ALICE, setOf(MOVIES)).outcome

            assertEquals(SyncOutcome.Cancelled, outcome)
            assertTrue(server.calls.isEmpty())
            assertNull(lastSuccess())
        }

    @Test
    fun onlyTheTickedLibrariesAreAsked() =
        runBlocking {
            val other = SyncWorld.source("s", 20, SHOWS)
            val both = SyncWorld.server(movies to library, SyncWorld.shows to other)
            repository.useAccount(ALICE)
            SyncWorld.engine(repository, both, clock).run(session, ALICE, setOf(MOVIES))
            both.calls.clear()
            clock.instant += 1.hours

            StayCurrentWorld.engine(repository, both, clock).incremental(session, ALICE, setOf(MOVIES))

            assertEquals(setOf(MOVIES), both.calls.map { it.library.id }.toSet())
        }

    @Test
    fun theFirstSyncRecordsWhenItsWalkBeganSoTheNextPassCoversALongWalk() =
        runBlocking {
            repository.useAccount(ALICE)
            server.respond = {
                clock.instant += 20.minutes
                null
            }

            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))

            assertEquals(EPOCH, lastSuccess())
        }

    @Test
    fun aLastSuccessInTheFutureStillCoversTheWindowAndIsReplacedByTheRealStart() =
        runBlocking {
            firstSyncDone()
            // The TV clock was set back: the recorded moment is ten hours ahead of "now".
            clock.instant -= 10.hours
            val now = clock.now()
            val changed = rename(12, "Saved a minute ago")
            library.saved[changed.id] = now - 1.minutes

            engine().incremental(session, ALICE, setOf(MOVIES))

            assertEquals("Saved a minute ago", storedNames()["m-00012"])
            // The older of the two moments is recorded, so the window never stays in the future.
            assertEquals(now, lastSuccess())
        }
}
