// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The in-memory port fake follows the port's contract, so tests of its users can trust it (ADR-0004). */
class InMemoryLibraryRepositoryTest {
    private val alice = LibraryAccount(ServerId("srv"), UserId("alice"))
    private val bob = LibraryAccount(ServerId("srv"), UserId("bob"))
    private val movies = ItemId("a".repeat(31) + "1")
    private val shows = ItemId("a".repeat(31) + "2")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun item(
        id: String,
        created: Instant? = epoch - 1.minutes,
        runtime: kotlin.time.Duration? = 90.minutes,
    ) = MediaItem(ItemId(id), id, ItemKind.Movie, runtime, null, dateCreated = created)

    @Test
    fun programInfoNamesLiveItemsOfTheAccountAndTheSeriesOfEpisodes() =
        runBlocking {
            val seriesId = "b".repeat(32)
            val episodeId = "c".repeat(32)
            val movieId = "d".repeat(32)
            val episode =
                MediaItem(
                    ItemId(episodeId),
                    "Episode One",
                    ItemKind.Episode,
                    30.minutes,
                    null,
                    seriesId = ItemId(seriesId),
                    seasonNumber = 2,
                    episodeNumber = 5,
                )
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, shows, episode)
            repository.seed(alice, movies, MediaItem(ItemId(movieId), "Test Movie A", ItemKind.Movie, 90.minutes, null))
            val gone = MediaItem(ItemId("e".repeat(32)), "Gone", ItemKind.Movie, null, null)
            repository.seed(alice, movies, gone, tombstoned = true)
            repository.seedSeriesName(alice, CanonicalId.parse(seriesId), "Test Series B")
            val ids = setOf(CanonicalId.parse(episodeId), CanonicalId.parse(movieId), CanonicalId.parse("e".repeat(32)))

            val info = repository.programInfo(alice, ids)

            assertEquals(setOf(CanonicalId.parse(episodeId), CanonicalId.parse(movieId)), info.keys)
            assertEquals("Test Series B", info.getValue(CanonicalId.parse(episodeId)).seriesName)
            assertEquals(5, info.getValue(CanonicalId.parse(episodeId)).episodeNumber)
            assertEquals(ScheduleItemKind.Movie, info.getValue(CanonicalId.parse(movieId)).kind)
            assertTrue(repository.programInfo(bob, ids).isEmpty())
        }

    @Test
    fun currentIdsGivesTheLiveItemsOfTheAccountOnly() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(alice, shows, item("d".repeat(32)))
            repository.seed(alice, shows, item("e".repeat(32)), tombstoned = true)
            val ids = setOf(CanonicalId.parse("d".repeat(32)), CanonicalId.parse("e".repeat(32)))

            assertEquals(setOf(CanonicalId.parse("d".repeat(32))), repository.currentIds(alice, ids))
            assertTrue(repository.currentIds(bob, ids).isEmpty())
        }

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

    private fun held(
        hex: Char,
        deletedAtMs: Long? = null,
    ) = ScheduleItem(
        id = CanonicalId.parse(hex.toString().repeat(32)),
        kind = ScheduleItemKind.Movie,
        runtimeMs = 5_400_000,
        createdAtMs = 1_000,
        deletedAtMs = deletedAtMs,
        premiereDate = null,
        productionYear = null,
        seriesId = null,
        seasonNumber = null,
        episodeNumber = null,
    )

    @Test
    fun scheduleItemsAreTheSeededOnesOfTheChosenLibrariesInIdOrderUntilTheirDeletion() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val cutoffMs = epoch.toEpochMilliseconds()
            repository.seedSchedule(alice, movies, held('b'), held('a'), held('c', deletedAtMs = cutoffMs - 1))
            repository.seedSchedule(alice, movies, held('d', deletedAtMs = cutoffMs))
            repository.seedSchedule(alice, shows, held('e'))
            repository.seedSchedule(bob, movies, held('f'))

            val items = repository.scheduleItems(alice, setOf(movies), epoch)

            assertEquals(listOf('a', 'b', 'd').map { it.toString().repeat(32) }, items.map { it.id.value })
        }

    @Test
    fun scheduleItemsAlsoReadTheValidRowsSavedThroughTheSyncPath() =
        runBlocking {
            val repository = InMemoryLibraryRepository()

            fun row(
                hex: Char,
                runtime: kotlin.time.Duration? = 90.minutes,
                kind: ItemKind = ItemKind.Movie,
            ) = MediaItem(
                ItemId(hex.toString().repeat(32)),
                "n",
                kind,
                runtime,
                null,
                dateCreated = epoch - 1.minutes,
            )
            repository.seed(alice, movies, row('c'), row('b'), row('d', runtime = null))
            repository.seed(alice, movies, row('e', kind = ItemKind.Series))
            repository.seed(alice, movies, row('f'), tombstoned = true)
            repository.seed(alice, movies, item("not-canonical"))
            repository.seedSchedule(alice, movies, held('a'))

            val items = repository.scheduleItems(alice, setOf(movies), epoch)

            assertEquals(listOf('a', 'b', 'c').map { it.toString().repeat(32) }, items.map { it.id.value })
        }

    @Test
    fun scheduleItemsFollowSelectionAndSignOutLikeTheOtherReads() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seedSchedule(alice, movies, held('a'))
            repository.seedSchedule(alice, shows, held('b'))
            repository.seedSchedule(bob, movies, held('c'))

            repository.select(alice, setOf(movies))
            assertEquals(emptyList(), repository.scheduleItems(alice, setOf(shows), epoch))

            repository.useAccount(alice)
            assertEquals(emptyList(), repository.scheduleItems(bob, setOf(movies), epoch))

            repository.forget(alice)
            assertEquals(emptyList(), repository.scheduleItems(alice, setOf(movies), epoch))
        }

    // The rule-input snapshot (spec 10, Unit 3).

    private fun id(hex: Char) = CanonicalId.parse(hex.toString().repeat(32))

    private val moviesId = CanonicalId.parse("a".repeat(32))
    private val showsId = CanonicalId.parse("b".repeat(32))
    private val moviesLib = ItemId(moviesId.value)
    private val showsLib = ItemId(showsId.value)

    private fun episode(
        hex: Char,
        series: Char?,
    ) = held(hex).copy(
        kind = ScheduleItemKind.Episode,
        seriesId = series?.let { id(it) },
        seasonNumber = 1,
        episodeNumber = 1,
    )

    @Suppress("LongParameterList") // A plain carrier for the fields a test sets, the rest left at their defaults.
    private fun ruleItem(
        item: ScheduleItem,
        library: CanonicalId = moviesId,
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        seasonId: CanonicalId? = null,
        studios: List<String> = emptyList(),
    ) = RuleItem(item, library, seasonId, rating, genres, tags, studios)

    private fun collection(
        hex: Char,
        movie: Char? = null,
    ) = RuleCollection(id(hex), setOfNotNull(movie?.let { id(it) }), emptySet(), emptySet(), emptySet())

    @Test
    fun anItemInALibraryWhoseIdIsNotCanonicalIsLeftOutOfBothReads() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val odd = ItemId("lib-odd")
            repository.seedSchedule(alice, odd, held('a'))
            repository.seedRule(alice, ruleItem(held('b')))

            val snapshot = repository.ruleSnapshot(alice, setOf(odd, moviesLib), epoch)

            assertEquals(listOf(id('b')), repository.scheduleItems(alice, setOf(odd, moviesLib), epoch).map { it.id })
            assertEquals(listOf(id('b')), snapshot.items.map { it.item.id })
        }

    @Test
    fun aRuleSnapshotEchoesTheCutoffAndTheLibrariesItWasAskedFor() =
        runBlocking {
            val repository = InMemoryLibraryRepository()

            val snapshot = repository.ruleSnapshot(alice, setOf(moviesLib, showsLib), epoch)

            assertEquals(epoch.toEpochMilliseconds(), snapshot.cutoffMs)
            assertEquals(setOf(moviesId, showsId), snapshot.libraries)
            assertEquals(emptyList(), snapshot.items)
            assertEquals(emptyMap(), snapshot.series)
            assertEquals(emptyMap(), snapshot.collections)
        }

    @Test
    fun aRuleSnapshotsItemsAreTheScheduleItemsWithWhatTheRulesRead() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val cutoffMs = epoch.toEpochMilliseconds()
            val genres = listOf("Drama", "Comedy")
            val rated = ruleItem(held('b'), rating = " ", genres = genres, tags = listOf("noir"), studios = listOf("S"))
            repository.seedRule(alice, rated, ruleItem(held('a'), rating = null))
            repository.seedRule(alice, ruleItem(held('c', deletedAtMs = cutoffMs - 1)))
            repository.seedRule(alice, ruleItem(held('d', deletedAtMs = cutoffMs)))
            repository.seedRule(alice, ruleItem(held('e'), library = showsId))
            repository.seedRule(bob, ruleItem(held('f')))

            val snapshot = repository.ruleSnapshot(alice, setOf(moviesLib), epoch)

            assertEquals(listOf('a', 'b', 'd').map { id(it) }, snapshot.items.map { it.item.id })
            assertEquals(snapshot.items.map { it.item }, repository.scheduleItems(alice, setOf(moviesLib), epoch))
            assertEquals(rated, snapshot.items[1])
            assertEquals(null, snapshot.items[0].officialRating)
        }

    @Test
    fun aRuleSnapshotReadsTheRowsSavedThroughTheSyncPathToo() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seed(
                alice,
                moviesLib,
                MediaItem(
                    ItemId("b".repeat(32)),
                    "n",
                    ItemKind.Movie,
                    90.minutes,
                    null,
                    dateCreated = epoch - 1.minutes,
                    officialRating = "",
                    genres = listOf("g"),
                    tags = listOf("t"),
                    studios = listOf("s"),
                ),
            )

            val items = repository.ruleSnapshot(alice, setOf(moviesLib), epoch).items

            assertEquals(listOf(id('b')), items.map { it.item.id })
            val only = items.single()
            assertEquals(
                listOf(moviesId, null, "", listOf("g"), listOf("t"), listOf("s")),
                listOf(only.libraryId, only.seasonId, only.officialRating, only.genres, only.tags, only.studios),
            )
        }

    @Test
    fun aRuleSnapshotHoldsTheSeriesOfTheReturnedEpisodesOnly() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seedRule(alice, ruleItem(episode('1', series = '5')), ruleItem(episode('2', series = '6')))
            repository.seedRule(alice, ruleItem(episode('3', series = '7'), library = showsId))
            repository.seedRule(alice, ruleItem(held('4')))
            repository.seedSeries(alice, RuleSeries(id('5'), "TV-14", listOf("Drama"), listOf("t")))
            repository.seedSeries(alice, RuleSeries(id('7'), null, emptyList(), emptyList()))
            repository.seedSeries(alice, RuleSeries(id('8'), null, emptyList(), emptyList()))

            val snapshot = repository.ruleSnapshot(alice, setOf(moviesLib), epoch)

            // '6' is unknown and has no entry; '7' is in a library not asked for; '8' has no returned episode.
            assertEquals(listOf(id('5')), snapshot.series.keys.toList())
            assertEquals(RuleSeries(id('5'), "TV-14", listOf("Drama"), listOf("t")), snapshot.series.getValue(id('5')))
        }

    @Test
    fun aTombstonedSeriesIsKeptUntilItsDeletionIsBeforeTheCutoff() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val cutoffMs = epoch.toEpochMilliseconds()
            repository.seedRule(alice, ruleItem(episode('1', series = '5')), ruleItem(episode('2', series = '4')))
            repository.seedSeries(alice, RuleSeries(id('5'), null, emptyList(), emptyList()), deletedAtMs = cutoffMs)
            val plain = RuleSeries(id('4'), null, emptyList(), emptyList())
            repository.seedSeries(alice, plain, deletedAtMs = cutoffMs - 1)

            val snapshot = repository.ruleSnapshot(alice, setOf(moviesLib), epoch)

            assertEquals(listOf(id('5')), snapshot.series.keys.toList())
        }

    @Test
    fun aRuleSnapshotHoldsTheCollectionsWhateverTheLibrariesAreAndDropsTheDeletedOnes() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val cutoffMs = epoch.toEpochMilliseconds()
            repository.seedCollection(alice, collection('c', movie = '9'))
            repository.seedCollection(alice, collection('b'), deletedAtMs = cutoffMs)
            repository.seedCollection(alice, collection('a'), deletedAtMs = cutoffMs - 1)
            repository.seedCollection(bob, collection('d'))

            val snapshot = repository.ruleSnapshot(alice, setOf(moviesLib), epoch)

            assertEquals(listOf(id('b'), id('c')), snapshot.collections.keys.toList())
            assertEquals(setOf(id('9')), snapshot.collections.getValue(id('c')).movies)
            assertEquals(emptyList(), repository.ruleSnapshot(alice, emptySet(), epoch).items)
        }

    @Test
    fun collectionsAreNotSyncedUntilTheFakeIsToldTheyAre() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            assertFalse(repository.ruleSnapshot(alice, setOf(moviesLib), epoch).collectionsSynced)

            repository.setCollectionsSynced(alice, true)

            assertTrue(repository.ruleSnapshot(alice, setOf(moviesLib), epoch).collectionsSynced)
            assertFalse(repository.ruleSnapshot(bob, setOf(moviesLib), epoch).collectionsSynced)
        }

    @Test
    fun aRuleSnapshotFollowsSelectionAndSignOutLikeTheOtherReads() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.seedRule(alice, ruleItem(held('a')))
            repository.seedRule(alice, ruleItem(held('b'), library = showsId))
            repository.seedCollection(alice, collection('c'))
            repository.seedCollection(bob, collection('d'))
            repository.setCollectionsSynced(alice, true)

            repository.select(alice, setOf(moviesLib))
            assertEquals(emptyList(), repository.ruleSnapshot(alice, setOf(showsLib), epoch).items)

            repository.useAccount(alice)
            assertEquals(emptyList(), repository.ruleSnapshot(bob, setOf(moviesLib), epoch).items)
            assertEquals(emptyMap(), repository.ruleSnapshot(bob, setOf(moviesLib), epoch).collections)

            repository.forget(alice)
            val after = repository.ruleSnapshot(alice, setOf(moviesLib), epoch)
            assertEquals(emptyList(), after.items)
            assertEquals(emptyMap(), after.collections)
            assertFalse(after.collectionsSynced)
        }

    @Test
    fun collectionNamesAreTheCurrentOnesOfCollectionsNotDeleted() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val one = CanonicalId.parse("1".padStart(32, '0'))
            val two = CanonicalId.parse("2".padStart(32, '0'))
            repository.seedCollectionName(alice, one, "Synthetic One")
            repository.seedCollectionName(alice, two, "Synthetic Two", deleted = true)
            repository.seedCollectionName(bob, two, "Synthetic Two")

            repository.seedCollectionName(alice, one, "Synthetic Renamed")
            val names = repository.collectionNames(alice)

            assertEquals(setOf(one), names.ids)
            assertEquals("Synthetic Renamed", names[one])
        }

    @Test
    fun theCollectionPassSignalIsNullUntilAPassCompletesThenCarriesItsTime() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            assertEquals(null, repository.collectionPassCompleted(alice).first())

            repository.completeCollectionPass(alice, epoch)

            assertEquals(epoch, repository.collectionPassCompleted(alice).first())
            assertEquals(null, repository.collectionPassCompleted(bob).first())
        }

    @Test
    fun theCollectionPassSignalGoesToNullWhenTheAccountIsForgottenOrAnotherBecomesActive() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.completeCollectionPass(alice, epoch)
            repository.completeCollectionPass(bob, epoch)

            repository.useAccount(bob)
            assertEquals(null, repository.collectionPassCompleted(alice).first())
            assertEquals(epoch, repository.collectionPassCompleted(bob).first())

            repository.forget(bob)
            assertEquals(null, repository.collectionPassCompleted(bob).first())
        }

    @Test
    fun aPassOfAnotherAccountDoesNotChangeThisAccountsSignal() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            repository.completeCollectionPass(alice, epoch)

            repository.completeCollectionPass(bob, epoch + 1.minutes)

            assertEquals(epoch, repository.collectionPassCompleted(alice).first())
        }

    // As the Room repository (#480): the pass that turns collectionsSynced true changes a snapshot, so it moves the
    // generation; a pass that finds it already true does not.
    @Test
    fun collectionsSyncedTurningTrueOrFalseMovesTheGenerationAndStayingPutDoesNot() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            val start = repository.contentGeneration(alice)

            repository.setCollectionsSynced(alice, true)
            val synced = repository.contentGeneration(alice)
            assertTrue(synced > start)

            repository.setCollectionsSynced(alice, true)
            repository.completeCollectionPass(alice, epoch)
            assertEquals(synced, repository.contentGeneration(alice))

            repository.setCollectionsSynced(alice, false)
            assertTrue(repository.contentGeneration(alice) > synced)
        }
}
