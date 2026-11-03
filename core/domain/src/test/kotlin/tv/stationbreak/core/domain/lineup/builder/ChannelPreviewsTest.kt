// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.ADDED_MS
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.LIBRARIES
import tv.stationbreak.core.domain.lineup.LIBRARY_ID
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.SharedSnapshots
import tv.stationbreak.core.domain.lineup.TWO_HOURS_MS
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.buildDay
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The preview of a draft comes from the real scheduler: the same pool, ceiling, policy and layout (spec 22). */
class ChannelPreviewsTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val shared = SharedSnapshots(CoroutineScope(SupervisorJob()))
    private val source = BuiltInScheduleSource(service, library, shared = shared) { LIBRARIES }
    private val previews = BuiltInChannelPreviews(service, library, shared) { LIBRARIES }
    private val keyLabels: suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String> =
        { _, sources -> sources.associateWith { it.key } }
    private val builder = ChannelBuildManager(service, { Seed(1uL) }, { "built" }, keyLabels)
    private val libraryItem = ItemId(LIBRARY_ID.value)

    init {
        runBlocking {
            library.delegate.useAccount(ACCOUNT)
            holding()
        }
    }

    private suspend fun holding(vararg extra: LineupChannel) {
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs, *extra)))
    }

    private fun movieItem(
        n: Int,
        runtimeMs: Long = TWO_HOURS_MS,
        createdAtMs: Long = ADDED_MS,
    ) = ScheduleItem(id(n), ScheduleItemKind.Movie, runtimeMs, createdAtMs, null, null, 2000, null, null, null)

    private fun film(
        n: Int,
        name: String = "Film $n",
        genres: List<String> = listOf("Comedy"),
        rating: String? = "R",
        item: ScheduleItem = movieItem(n),
    ) {
        library.delegate.seedRule(ACCOUNT, RuleItem(item, LIBRARY_ID, null, rating, genres, emptyList()))
        library.delegate.seed(ACCOUNT, libraryItem, MediaItem(ItemId(id(n).value), name, ItemKind.Movie, null, null))
    }

    private fun episode(
        n: Int,
        show: Int,
        season: Int,
        number: Int,
        premiere: String,
    ) {
        val item =
            ScheduleItem(
                id(n),
                ScheduleItemKind.Episode,
                1_800_000L,
                ADDED_MS,
                null,
                premiere,
                2000,
                id(show),
                season,
                number,
            )
        library.delegate.seedRule(ACCOUNT, RuleItem(item, LIBRARY_ID, null, "PG", listOf("Drama"), emptyList()))
        val media =
            MediaItem(
                ItemId(id(n).value),
                "Episode $n",
                ItemKind.Episode,
                null,
                null,
                seriesId = ItemId(id(show).value),
                seasonNumber = season,
                episodeNumber = number,
            )
        library.delegate.seed(ACCOUNT, libraryItem, media)
    }

    private fun show(
        n: Int,
        name: String,
    ) {
        library.delegate.seedSeries(ACCOUNT, RuleSeries(id(n), "PG", listOf("Drama"), emptyList()))
        library.delegate.seedSeriesName(ACCOUNT, id(n), name)
    }

    private fun draft(
        vararg sources: DraftSource,
        seed: ULong = 11uL,
        kids: Boolean = false,
        order: PlayOrder = PlayOrder.Shuffle,
        filters: DraftFilters = DraftFilters(),
    ) = ChannelDraft(
        "Mine",
        sources.toList().ifEmpty { listOf(comedy) },
        emptyList(),
        filters,
        order = order,
        kids = kids,
        seed = Seed(seed),
    )

    private val comedy = DraftSource.Genre("Comedy", "Comedy")

    private fun preview(
        draft: ChannelDraft,
        at: Instant = NOON,
    ) = runBlocking { previews.preview(ACCOUNT, draft, at) }

    private val ChannelPreview.next: List<PreviewItem>
        get() = (this as? ChannelPreview.Ready)?.next.orEmpty()
    private val ChannelPreview.itemCount: Int
        get() = (this as? ChannelPreview.Ready)?.itemCount ?: 0
    private val ChannelPreview.totalRuntimeMinutes: Long
        get() = (this as? ChannelPreview.Ready)?.totalRuntimeMinutes ?: 0L
    private val ChannelPreview.skipped: List<SkippedPick>
        get() =
            when (this) {
                is ChannelPreview.Ready -> skipped
                is ChannelPreview.NothingFits -> skipped
                ChannelPreview.Unavailable -> emptyList()
            }
    private val ChannelPreview.nothingFits: Boolean get() = this is ChannelPreview.NothingFits
    private val ChannelPreview.unavailable: Boolean get() = this === ChannelPreview.Unavailable

    private fun ids(preview: ChannelPreview): List<CanonicalId> = preview.next.map { it.id }

    // Builds [draft] into the lineup through the builder and reads what the real source airs from [at].
    private fun airedAs(
        draft: ChannelDraft,
        at: Instant = NOON,
    ): List<CanonicalId> =
        runBlocking {
            service.refresh(ACCOUNT, LIBRARIES)
            val built = (builder.create(ACCOUNT, draft) as BuildResult.Done).id
            airs(built, at)
        }

    // What the real source airs on [channel] from [at]: the block on air and the 11 after it.
    private suspend fun airs(
        channel: ChannelId,
        at: Instant = NOON,
    ): List<CanonicalId> {
        val onAir = checkNotNull(source.onAir(channel, at, ChannelPreviews.NEXT - 1))
        return (listOf(onAir.now) + onAir.next).mapNotNull { (it.airing as? Airing.Program)?.itemId }
    }

    private fun sevenComedies() = (1..7).forEach { film(it) }

    @Test
    fun thePreviewUsesTheDraftsSeed() {
        sevenComedies()

        val one = preview(draft(seed = 1uL))
        val two = preview(draft(seed = 2uL))

        assertEquals(ChannelPreviews.NEXT, one.next.size)
        assertNotEquals(ids(one), ids(two), "two seeds, two Shuffle orders")
        assertEquals(ids(one), preview(draft(seed = 1uL)).let(::ids), "the same draft previews the same")
        assertEquals(ids(one), airedAs(draft(seed = 1uL)), "an unchanged draft saved airs the previewed order")
    }

    @Test
    fun thePreviewIsTheDayTheChannelWouldAir() {
        sevenComedies()
        film(8, genres = listOf("Drama"))
        val mine = draft(comedy, seed = 5uL)

        assertEquals(ids(preview(mine)), airedAs(mine))
    }

    @Test
    fun thePreviewPolicyAndCeilingComeFromTheLineupsFunctions() {
        // Made for kids, rated G, PG, PG-13 and R: a kids draft with a PG-13 ceiling previews under PG.
        film(1, genres = listOf("Animation", "Kids"), rating = "G")
        film(2, genres = listOf("Animation", "Kids"), rating = "PG")
        film(3, genres = listOf("Animation", "Kids"), rating = "PG-13")
        film(4, genres = listOf("Animation", "Kids"), rating = "R")
        film(5, genres = listOf("Animation", "Drama"), rating = "G")
        val kids = DraftSource.Genre("Animation", "Animation")
        val mine = draft(kids, kids = true, filters = DraftFilters(maxRating = "PG-13"))

        val shown = preview(mine)

        assertEquals(2, shown.itemCount, "G and PG, made for kids; the PG-13 and R and the grown-up one are out")
        assertTrue(ids(shown).toSet() == setOf(id(1), id(2)))
        assertEquals(ids(shown), airedAs(mine), "equal to the channel the lineup would build")
    }

    @Test
    fun normalisingAKidsCeilingNeverChangesThePreview() {
        film(1, genres = listOf("Animation", "Kids"), rating = "G")
        film(2, genres = listOf("Animation", "Kids"), rating = "PG")
        film(3, genres = listOf("Animation", "Kids"), rating = "R")
        val kids = DraftSource.Genre("Animation", "Animation")
        val loose = draft(kids, kids = true, filters = DraftFilters(maxRating = "R"))
        val normalised = loose.kidsNormalised()

        assertEquals("PG", normalised.filters.maxRating)
        assertEquals(ids(preview(normalised)), ids(preview(loose)))
        assertEquals(ids(preview(normalised)), airedAs(normalised), "what create would then air")
    }

    @Test
    fun aRotationDraftPreviewsFilmsOnePerTurn() {
        (1..4).forEach { film(it, genres = listOf("A")) }
        (5..8).forEach { film(it, genres = listOf("B")) }
        val first = setOf(id(1), id(2), id(3), id(4))
        library.delegate.seedCollection(
            ACCOUNT,
            RuleCollection(id(0xC1), first, emptySet(), emptySet(), emptySet()),
            RuleCollection(id(0xC2), (5..8).map { id(it) }.toSet(), emptySet(), emptySet(), emptySet()),
        )
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        val mine =
            draft(
                DraftSource.Collection(id(0xC1).value, "One"),
                DraftSource.Collection(id(0xC2).value, "Two"),
                order = PlayOrder.Rotation,
            )

        val shown = preview(mine)

        val turns = ids(shown).take(8).map { if (it in first) 1 else 2 }
        assertTrue(turns.zipWithNext().all { (a, b) -> a != b }, "one film per turn: $turns")
        assertEquals(ids(shown), airedAs(mine))
    }

    @Test
    fun anInOrderDraftPreviewsOldestShowFirst() {
        show(0x51, "Newer Show")
        show(0x52, "Older Show")
        episode(0x61, 0x51, 1, 1, "2010-01-01")
        episode(0x62, 0x51, 1, 2, "2010-01-08")
        episode(0x63, 0x52, 1, 1, "2000-01-01")
        episode(0x64, 0x52, 1, 2, "2000-01-08")
        val newer = DraftSource.Show(id(0x51).value, "Newer")
        val older = DraftSource.Show(id(0x52).value, "Older")
        val mine = draft(newer, older, order = PlayOrder.InOrder)

        val shown = preview(mine)

        assertEquals(listOf(id(0x63), id(0x64), id(0x61), id(0x62)), ids(shown).take(4))
        assertEquals("Older Show", shown.next.first().seriesName)
        assertEquals(1, shown.next.first().season)
        assertEquals(30, shown.next.first().minutes)
        assertEquals(ids(shown), airedAs(mine))
    }

    @Test
    fun itCountsItemsAndRuntimeOfThePool() {
        sevenComedies()

        val shown = preview(draft())

        assertEquals(7, shown.itemCount)
        assertEquals(7L * 120, shown.totalRuntimeMinutes)
        assertEquals(120, shown.next.first().minutes)
        assertTrue(!shown.nothingFits)
    }

    @Test
    fun aDraftThatMatchesNothingSaysNothingFits() {
        sevenComedies()

        val shown = preview(draft(DraftSource.Genre("Nothing Like It", "Nothing Like It")))

        assertTrue(shown.nothingFits)
        assertEquals(0, shown.itemCount)
        assertTrue(shown.next.isEmpty() && shown.skipped.isEmpty())
    }

    @Test
    fun anEmptyPoolReadsOnlyTodaysSnapshot() {
        sevenComedies()
        library.snapshotCutoffs.clear()

        preview(draft(DraftSource.Genre("Nothing Like It", "Nothing Like It")))

        assertTrue(library.snapshotCutoffs.size <= 1, "read ${library.snapshotCutoffs.size} snapshots")
    }

    @Test
    fun aKidsDraftListsPicksTheHoldLeavesOut() {
        film(1, "Good Film", genres = listOf("Kids"), rating = "G")
        film(2, "Too Strong", genres = listOf("Kids"), rating = "R")
        film(3, "Not For Kids", genres = listOf("Drama"), rating = "PG")
        val mine =
            draft(
                DraftSource.Pick(id(1).value, "Good Film"),
                DraftSource.Pick(id(2).value, "Too Strong"),
                DraftSource.Pick(id(3).value, "Not For Kids"),
                kids = true,
            )

        val shown = preview(mine)

        assertEquals(
            listOf(
                SkippedPick("Too Strong", SkipReason.AboveKidsRating),
                SkippedPick("Not For Kids", SkipReason.NotMadeForKids),
            ),
            shown.skipped,
        )
        assertTrue(ids(shown).all { it == id(1) }, "neither refused pick is in next")
        assertEquals(1, shown.itemCount)
    }

    @Test
    fun aKidsChannelNeverAirsAPickedItemTheHoldRefuses() {
        film(2, "Too Strong", genres = listOf("Kids"), rating = "R")
        val mine = draft(DraftSource.Pick(id(2).value, "Too Strong"), kids = true)

        val shown = preview(mine)

        assertTrue(shown.nothingFits && shown.next.isEmpty())
        assertEquals(listOf(SkippedPick("Too Strong", SkipReason.AboveKidsRating)), shown.skipped)
    }

    @Test
    fun aGrownUpDraftSkipsNothing() {
        film(2, "Too Strong", genres = listOf("Kids"), rating = "R")

        val shown = preview(draft(DraftSource.Pick(id(2).value, "Too Strong")))

        assertTrue(shown.skipped.isEmpty())
        assertEquals(1, shown.itemCount)
    }

    @Test
    fun theNextEpochIsUsedWhenTheDayIsNearlyOver() {
        sevenComedies()
        val late = Instant.parse("2026-10-20T05:50:00Z")
        val mine = draft(seed = 3uL)

        val shown = preview(mine, late)

        assertEquals(ChannelPreviews.NEXT, shown.next.size, "the rest of today is only a block or two")
        assertEquals(ids(shown), airedAs(mine, late))
    }

    @Test
    fun aBurstOfPreviewsReadsTheSnapshotOnce() {
        sevenComedies()
        preview(draft())
        val after = shared.cache.reads

        repeat(5) { preview(draft(seed = it.toULong())) }

        assertEquals(after, shared.cache.reads)
    }

    @Test
    fun previewNeverRunsOnTheCallersThread() {
        sevenComedies()
        val seen = mutableMapOf<String, String>()
        val watching =
            object : LibraryRepository by library {
                override suspend fun contentGeneration(account: LibraryAccount): Long {
                    seen["contentGeneration"] = Thread.currentThread().name
                    return library.contentGeneration(account)
                }

                override suspend fun programInfo(
                    account: LibraryAccount,
                    ids: Set<CanonicalId>,
                ): Map<CanonicalId, ProgramInfo> {
                    seen["programInfo"] = Thread.currentThread().name
                    return library.programInfo(account, ids)
                }
            }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "preview-thread") }
        val ownShared = SharedSnapshots(CoroutineScope(SupervisorJob()))
        val dispatcher = executor.asCoroutineDispatcher()
        val elsewhere = BuiltInChannelPreviews(service, watching, ownShared, dispatcher) { LIBRARIES }

        val shown = runBlocking { elsewhere.preview(ACCOUNT, draft(), NOON) }

        executor.shutdown()
        assertTrue(shown.next.isNotEmpty())
        // The coroutine debugger may append " @coroutine#n" to the thread's name.
        assertEquals(setOf("contentGeneration", "programInfo"), seen.keys)
        assertTrue(seen.values.all { it.startsWith("preview-thread") }, "ran on $seen")
    }

    @Test
    fun aDraftThatCannotBeReadIsUnavailableNotNothingFits() {
        sevenComedies()
        val kept = KeptSettings("Laughs", listOf(KeptItem.NoCaps))
        val noLibraries = BuiltInChannelPreviews(service, library, shared) { null }
        val failing =
            object : LibraryRepository by library {
                override suspend fun contentGeneration(account: LibraryAccount): Long = error("the library is gone")
            }
        val broken = BuiltInChannelPreviews(service, failing, shared) { LIBRARIES }

        val withoutSource = preview(draft().copy(kept = kept))
        val withoutLibraries = runBlocking { noLibraries.preview(ACCOUNT, draft(), NOON) }
        val unreadable = runBlocking { broken.preview(ACCOUNT, draft(), NOON) }

        listOf(withoutSource, withoutLibraries, unreadable).forEach {
            assertTrue(it.unavailable && !it.nothingFits && it.next.isEmpty(), "$it")
        }
        assertTrue(!preview(draft()).unavailable)
    }

    private fun dramas() {
        show(0x51, "Show One")
        show(0x52, "Show Two")
        (1..4).forEach { episode(0x60 + it, 0x51, 1, it, "2000-01-0$it") }
        (1..4).forEach { episode(0x70 + it, 0x52, 1, it, "2010-01-0$it") }
    }

    private fun dramaRules() =
        rules("Dramas", Source.Series(id(0x51)), Source.Series(id(0x52)))
            .copy(ordering = Ordering(OrderingMode.RandomInOrder))

    @Test
    fun aCopyOfAStarterWithKeptSettingsPreviewsWhatCreateThenAirs() {
        dramas()
        val dramas = starter("dramas", 230, ChannelState.On, dramaRules())
        runBlocking { holding(dramas) }
        val opened = runBlocking { builder.open(ACCOUNT, dramas.id, copy = true) } as OpenResult.Opened
        val copy = opened.draft.copy(name = "Dramas (copy)")
        assertTrue(copy.kept?.items?.contains(KeptItem.Order(KeptOrder.RandomInOrder)) == true)

        val shown = runBlocking { previews.preview(ACCOUNT, copy, NOON) }
        val dropped = runBlocking { previews.preview(ACCOUNT, copy.copy(kept = null), NOON) }

        assertEquals(ids(shown), airedAs(copy))
        assertNotEquals(ids(shown), ids(dropped), "the kept order is not the builder's Shuffle")
    }

    @Test
    fun anEditOfACustomChannelPreviewsWhatSaveThenAirs() {
        dramas()
        val mine = channel("custom:mine", 620, ChannelState.On, ChannelSource.Custom(dramaRules()))
        runBlocking { holding(mine) }
        val opened = runBlocking { builder.open(ACCOUNT, mine.id, copy = false) } as OpenResult.Opened
        val edited = opened.draft.copy(name = "Renamed")

        val shown = runBlocking { previews.preview(ACCOUNT, edited, NOON) }
        val aired =
            runBlocking {
                assertEquals(BuildResult.Done(mine.id), builder.save(ACCOUNT, mine.id, edited))
                airs(mine.id)
            }

        assertEquals(ids(shown), aired)
        assertEquals(ChannelPreviews.NEXT, shown.next.size)
    }

    @Test
    fun aNextEpochItemTakesItsRuntimeFromThatEpochsDay() {
        sevenComedies()
        // Added after today's cut-off and before tomorrow's: in tomorrow's pool only, and not as long as the others.
        val between = Instant.parse("2026-10-19T14:00:00Z").toEpochMilliseconds()
        (20..24).forEach { film(it, item = movieItem(it, runtimeMs = 3 * TWO_HOURS_MS / 2, createdAtMs = between)) }
        val late = Instant.parse("2026-10-20T05:50:00Z")

        val shown = preview(draft(seed = 4uL), late)

        val added = shown.next.filter { it.id.value >= id(20).value }
        assertTrue(added.isNotEmpty(), "some of tomorrow's own items are listed")
        assertTrue(added.all { it.minutes == 180 }, "$added")
        assertEquals(7, shown.itemCount, "the count is of the epoch the time is in")
    }

    @Test
    fun aShortEpochDoesNotTouchTheLineupsSnapshots() {
        sevenComedies()
        val oddTiming = Timing(null, false, 12, null, null, null, BroadcastDay(0, "06:00"))
        val oddRules = rules("Odd", Source.Genre("Comedy")).copy(timing = oddTiming)
        val odd = starter("odd", 330, ChannelState.UserHidden, oddRules)
        runBlocking { holding(odd) }
        val opened = runBlocking { builder.open(ACCOUNT, odd.id, copy = true) } as OpenResult.Opened
        assertTrue(
            opened.draft.kept
                ?.items
                ?.contains(KeptItem.EpochHours(12)) == true,
        )
        preview(draft())
        val reads = shared.cache.reads

        val shown = runBlocking { previews.preview(ACCOUNT, opened.draft, NOON) }

        assertTrue(shown.next.isNotEmpty() && !shown.unavailable)
        assertEquals(reads, shared.cache.reads, "its own cut-offs were read, not kept")
    }

    @Test
    fun aCappedShuffleOfFilmsInACollectionPreviewsWhatItAirs() {
        (1..8).forEach { film(it, genres = listOf("A")) }
        library.delegate.seedCollection(
            ACCOUNT,
            RuleCollection(id(0xC1), (1..4).map { id(it) }.toSet(), emptySet(), emptySet(), emptySet()),
        )
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        val mine = draft(DraftSource.Genre("A", "A"), DraftSource.Collection(id(0xC1).value, "Box Set"), seed = 9uL)

        assertEquals(ids(preview(mine)), airedAs(mine))
    }

    @Test
    fun aCappedShuffleOfEpisodesPreviewsWhatItAirs() {
        show(0x51, "Show One")
        show(0x52, "Show Two")
        (1..6).forEach { episode(0x60 + it, 0x51, 1, it, "2000-01-0$it") }
        (1..6).forEach { episode(0x70 + it, 0x52, 1, it, "2010-01-0$it") }
        val mine = draft(DraftSource.Show(id(0x51).value, "One"), DraftSource.Show(id(0x52).value, "Two"), seed = 6uL)

        assertEquals(ids(preview(mine)), airedAs(mine))
    }

    @Test
    fun aDayIsListedFromTheTimeAndAnOutsideTimeIsHandled() {
        sevenComedies()
        val settings = rules("X", Source.Genre("Comedy")).toSchedulerSettings()
        val n = Epochs.epochNumber(settings, NOON.toEpochMilliseconds())
        val items = (1..3).map { movieItem(it) }
        val layout = buildDay(settings, items, n, FilmCollections.of(emptyMap()))

        val all = airedFrom(layout, items, null)
        val middle = layout.blocks[2]
        val fromMiddle = airedFrom(layout, items, middle.startMs + 1)

        assertEquals(layout.blocks.size, all.size)
        assertEquals((middle.content as ScheduleContent.Program).itemId, fromMiddle.first().id)
        assertEquals(all.drop(2), fromMiddle)
        assertEquals(all, airedFrom(layout, items, layout.startMs - 1), "before the day: all of it")
        assertTrue(airedFrom(layout, items, layout.endMs).isEmpty(), "at its end: nothing, the next epoch is next")
        assertTrue(airedFrom(layout, items, layout.endMs + 1).isEmpty())
    }

    @Test
    fun aMinuteIsAMinute() {
        film(1, item = movieItem(1, runtimeMs = 90.minutes.inWholeMilliseconds))

        assertEquals(90, preview(draft()).next.first().minutes)
    }

    @Test
    fun aCancelledPreviewStopsBeforeTheNextEpoch() {
        sevenComedies()
        preview(draft())
        var generations = 0
        lateinit var preview: Job
        val watching =
            object : LibraryRepository by library {
                override suspend fun contentGeneration(account: LibraryAccount): Long {
                    // The first epoch is being read: the viewer has moved on.
                    if (++generations == 1) preview.cancel()
                    return library.contentGeneration(account)
                }
            }
        val cancellable = BuiltInChannelPreviews(service, watching, shared) { LIBRARIES }

        runBlocking {
            preview = launch(start = CoroutineStart.LAZY) { cancellable.preview(ACCOUNT, draft(), NOON) }
            preview.start()
            preview.join()
        }

        assertTrue(preview.isCancelled)
        assertEquals(1, generations, "the next epoch was not read")
    }

    @Test
    fun theSealedAnswersAreTheRightKinds() {
        sevenComedies()

        assertTrue(preview(draft()) is ChannelPreview.Ready)
        assertTrue(preview(draft(DraftSource.Genre("Nothing Like It", "x"))) is ChannelPreview.NothingFits)
        val orphan = draft().copy(kept = KeptSettings("x", listOf(KeptItem.NoCaps)))
        assertEquals(ChannelPreview.Unavailable, preview(orphan))
    }
}
