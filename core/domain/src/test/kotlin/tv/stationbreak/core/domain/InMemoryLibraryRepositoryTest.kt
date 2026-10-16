// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The in-memory port fake follows the port's contract, so tests of its users can trust it (ADR-0004). */
class InMemoryLibraryRepositoryTest {
    private val alice = LibraryAccount(ServerId("srv"), UserId("alice"))
    private val bob = LibraryAccount(ServerId("srv"), UserId("bob"))
    private val movies = ItemId("lib-movies")
    private val shows = ItemId("lib-shows")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun item(
        id: String,
        created: Instant? = epoch - 1.minutes,
        runtime: kotlin.time.Duration? = 90.minutes,
    ) = MediaItem(ItemId(id), id, ItemKind.Movie, runtime, null, dateCreated = created)

    @Test
    fun aSnapshotHoldsLiveUsableItemsCreatedBeforeTheInstantOfTheChosenLibraries() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("b"), item("a"), item("late", created = epoch + 1.minutes))
            repository.seed(alice, movies, item("gone"), tombstoned = true)
            repository.seed(alice, movies, item("empty", runtime = null), item("zero", runtime = 0.minutes))
            repository.seed(alice, shows, item("other-library"))

            val snapshot = repository.snapshot(alice, setOf(movies), epoch)

            assertEquals(listOf("a", "b"), snapshot.map { it.id.value })
            assertEquals(2, repository.excludedCount(alice, setOf(movies)))
        }

    @Test
    fun anItemWithNoCreationDateIsNeverInASnapshot() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("undated", created = null))

            assertEquals(emptyList(), repository.snapshot(alice, setOf(movies), epoch))
        }

    @Test
    fun anotherAccountSeesNothingOfThisOne() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("a"))

            assertEquals(emptyList(), repository.snapshot(bob, setOf(movies), epoch))
        }

    @Test
    fun usingAnAccountDropsEveryOtherAccountsLibrary() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("a"))
            repository.seed(bob, movies, item("b"))

            repository.useAccount(bob)

            assertEquals(emptyList(), repository.snapshot(alice, setOf(movies), epoch))
            assertEquals(listOf("b"), repository.snapshot(bob, setOf(movies), epoch).map { it.id.value })
        }

    @Test
    fun forgettingAnAccountDropsOnlyThatAccount() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("a"))
            repository.seed(bob, movies, item("b"))

            repository.forget(alice)

            assertEquals(emptyList(), repository.snapshot(alice, setOf(movies), epoch))
            assertEquals(1, repository.snapshot(bob, setOf(movies), epoch).size)
        }

    @Test
    fun selectingLibrariesPrunesTheOnesLeftOutAndMarksNewOnesPending() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, movies, item("a"))
            repository.seed(alice, shows, item("s"))

            repository.select(alice, setOf(shows, ItemId("lib-new")))

            assertEquals(emptyList(), repository.snapshot(alice, setOf(movies), epoch))
            assertEquals(setOf(ItemId("lib-new")), repository.pendingLibraries(alice))
        }

    @Test
    fun theStatusStartsNeverSyncedAndShowsWhatItWasTold() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            assertEquals(LibraryStatus(SyncState.NeverSynced, null), repository.status(alice).first())

            val done = LibraryStatus(SyncState.Idle, epoch)
            repository.setStatus(alice, done)

            assertEquals(done, repository.status(alice).first())
        }
}
