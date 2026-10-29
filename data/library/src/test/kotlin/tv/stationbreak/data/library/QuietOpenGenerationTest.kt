// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * #480: the run every app open makes (reconcile, collection pass, purge; no incremental pass) over a library that did
 * not change must leave the content generation where it was, in a world shaped like a real Jellyfin library: canonical
 * IDs, two kinds of library, series and their episodes, an item two libraries share, an ID the walk cannot return, and
 * BoxSets holding every kind of member (some outside the chosen libraries, a nested one, an empty one).
 */
class QuietOpenGenerationTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val family = ItemId(hexId(0xB003))
    private val libraries =
        listOf(
            SyncWorld.movies,
            SyncWorld.shows,
            Library(family, "Family", LibraryKind.Movies),
        )
    private val shared = movie(0x40, family)
    private val movies = ListLibrarySource(((1..30).map { movie(it, MOVIES) } + shared).toMutableList())
    private val familyMovies =
        ListLibrarySource((listOf(shared) + (0x41..0x45).map { movie(it, family) }).toMutableList())
    private val episodes =
        ListLibrarySource(
            (0 until 20)
                .map { n -> episode(0x600 + n, series = if (n < 10) 0x500 else 0x501, number = n % 10 + 1) }
                .toMutableList(),
        )
    private val fake = FakeMediaServer()
    private val server =
        PagedMediaServer(libraries, mapOf(MOVIES to movies, SHOWS to episodes, family to familyMovies), fake).also {
            it.seriesSources[SHOWS] = SeriesSource(mutableListOf(series(hexId(0x500)), series(hexId(0x501))))
        }
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server, repository)
    private val settings =
        InMemoryServerSettings(ServerRecord(SyncWorld.session.address), libraries.map { it.id }.toSet())
    private val lines = mutableListOf<String>()
    private val runner = StayCurrentRunner(settings, sessions, server, repository, clock, report = { lines += it })
    private val open = SyncPlan(incremental = false, reconcile = true)
    private val both = SyncPlan(incremental = true, reconcile = true)

    init {
        // Listed by the ID listing, never returned by the walk: the reconcile looks for it on every run.
        movies.items += movie(0x700, MOVIES)
        server.hiddenFromItems = setOf(ItemId(hexId(0x700)))
        val boxes = server.collectionSource
        serve(1, (1..5).map { member(it, CollectionMemberKind.Movie) })
        serve(
            2,
            listOf(
                member(3, CollectionMemberKind.Movie),
                member(0x40, CollectionMemberKind.Movie),
                member(0x600, CollectionMemberKind.Episode),
                member(0x500, CollectionMemberKind.Series),
                member(0x550, CollectionMemberKind.Season),
                member(0x900, CollectionMemberKind.Movie), // outside every chosen library
                CollectionMember(box(3), CollectionMemberKind.Collection),
                member(0x901, CollectionMemberKind.Other),
            ),
        )
        serve(3, emptyList())
        serve(4, listOf(member(0x902, CollectionMemberKind.Other)))
        boxes.collections.forEach { boxes.saved[it.id] = clock.now() - 1.days }
    }

    @After
    fun close() = repository.close()

    private fun movie(
        n: Int,
        library: ItemId,
    ) = MediaItem(
        ItemId(hexId(n)),
        "Movie $n",
        ItemKind.Movie,
        90.minutes,
        library,
        dateCreated = EPOCH - 2.days,
        genres = listOf("g${n % 3}"),
    )

    private fun episode(
        n: Int,
        series: Int,
        number: Int,
    ) = MediaItem(
        ItemId(hexId(n)),
        "Episode $n",
        ItemKind.Episode,
        30.minutes,
        SHOWS,
        dateCreated = EPOCH - 2.days,
        seriesId = ItemId(hexId(series)),
        seasonNumber = 1,
        episodeNumber = number,
    )

    private fun member(
        n: Int,
        kind: CollectionMemberKind,
    ) = CollectionMember(ItemId(hexId(n)), kind)

    private fun box(n: Int) = ItemId(hexId(0x1000 + n))

    private fun serve(
        n: Int,
        members: List<CollectionMember>,
    ) {
        server.collectionSource.collections += CollectionInfo(box(n), "Box $n")
        server.collectionSource.members[box(n)] = members.toMutableList()
    }

    private suspend fun generation() = repository.contentGeneration(ALICE)

    // A run whose passes all completed, and whose log says it wrote nothing: a quiet result must not come from a run
    // that stopped early.
    private suspend fun quiet(plan: SyncPlan) {
        lines.clear()
        assertEquals(SyncVerdict.Success, runner.run(plan))
        assertEquals("the collection pass completed", SyncOutcome.Completed, runner.lastCollectionPass?.outcome)
        val incremental = if (plan.incremental) "0/0" else "none"
        assertEquals(
            "StayCurrentWrote generation=0 incremental=$incremental reconcile=0/0/0/0/0 collections=0/0/0/0",
            lines.single { it.startsWith("StayCurrentWrote") },
        )
    }

    private suspend fun settled() {
        sessions.save(SyncWorld.session)
        fake.resumeResult = ServerResult.Success(SyncWorld.session)
        val verdict = FirstSyncRunner(settings, sessions, server, repository, clock).run(attempt = 0)
        check(verdict == SyncVerdict.Success) { "the first sync should finish: $verdict" }
        clock.instant += 1.hours
        runner.run(both)
        clock.instant += 1.hours
        runner.run(open)
    }

    @Test
    fun anOpenThatFindsADeletedItemSaysWhatItWroteAndHowFarTheGenerationMoved() =
        runBlocking {
            settled()
            movies.items.removeAt(0)
            lines.clear()
            clock.instant += 1.minutes

            runner.run(open)

            assertEquals(
                "StayCurrentWrote generation=1 incremental=none reconcile=1/0/0/0/0 collections=0/0/0/0",
                lines.single { it.startsWith("StayCurrentWrote") },
            )
        }

    @Test
    fun anOpenOverAnUnchangedLibraryLeavesTheGenerationAlone() =
        runBlocking {
            settled()
            val before = generation()
            val passBefore = repository.collectionPassCompleted(ALICE).first()

            clock.instant += 1.minutes
            quiet(open)
            assertEquals("a quiet open, a minute later", before, generation())

            clock.instant += 7.hours
            quiet(open)
            assertEquals("a quiet open that re-lists every collection", before, generation())

            clock.instant += 7.hours
            quiet(both)
            assertEquals("a quiet periodic run", before, generation())

            clock.instant += 40.days
            quiet(open)
            assertEquals("a quiet open after the retention, which purges", before, generation())
            assertNotEquals(
                "every completed pass records its own start",
                passBefore,
                repository.collectionPassCompleted(ALICE).first(),
            )
        }
}
