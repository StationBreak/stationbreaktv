// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.asCoroutineDispatcher
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
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.TWO_HOURS_MS
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
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
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }
    private val previews = BuiltInChannelPreviews(service, library, source) { LIBRARIES }
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

    private fun film(
        n: Int,
        name: String = "Film $n",
        genres: List<String> = listOf("Comedy"),
        rating: String? = "R",
        runtimeMs: Long = TWO_HOURS_MS,
    ) {
        val item = ScheduleItem(id(n), ScheduleItemKind.Movie, runtimeMs, ADDED_MS, null, null, 2000, null, null, null)
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

    private fun ids(preview: ChannelPreview): List<CanonicalId> = preview.next.map { it.id }

    // Builds [draft] into the lineup through the builder and reads what the real source airs from [at].
    private fun airedAs(
        draft: ChannelDraft,
        at: Instant = NOON,
    ): List<CanonicalId> =
        runBlocking {
            service.refresh(ACCOUNT, LIBRARIES)
            val built = (builder.create(ACCOUNT, draft) as BuildResult.Done).id
            val onAir = checkNotNull(source.onAir(built, at, ChannelPreviews.NEXT - 1))
            (listOf(onAir.now) + onAir.next).mapNotNull { (it.airing as? Airing.Program)?.itemId }
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
        val after = source.snapshots.reads

        repeat(5) { preview(draft(seed = it.toULong())) }

        assertEquals(after, source.snapshots.reads)
    }

    @Test
    fun previewNeverRunsOnTheCallersThread() {
        sevenComedies()
        var readOn: String? = null
        val watching =
            object : LibraryRepository by library {
                override suspend fun ruleSnapshot(
                    account: LibraryAccount,
                    libraries: Set<ItemId>,
                    cutoff: Instant,
                ): RuleSnapshot {
                    readOn = Thread.currentThread().name
                    return library.ruleSnapshot(account, libraries, cutoff)
                }
            }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "preview-thread") }
        val own = BuiltInScheduleSource(service, watching) { LIBRARIES }
        val dispatcher = executor.asCoroutineDispatcher()
        val elsewhere = BuiltInChannelPreviews(service, watching, own.snapshots, { LIBRARIES }, dispatcher)

        val shown = runBlocking { elsewhere.preview(ACCOUNT, draft(), NOON) }

        executor.shutdown()
        assertTrue(shown.next.isNotEmpty())
        assertNotEquals(Thread.currentThread().name, readOn)
        assertTrue(readOn?.startsWith("DefaultDispatcher") == true || readOn == "preview-thread", "read on $readOn")
    }

    @Test
    fun aKeptDraftWithoutItsSourceIsAnEmptyPreview() {
        sevenComedies()
        val kept = KeptSettings("Laughs", listOf(KeptItem.NoCaps))

        val shown = preview(draft().copy(kept = kept))

        assertTrue(shown.nothingFits)
    }

    @Test
    fun aMinuteIsAMinute() {
        film(1, runtimeMs = 90.minutes.inWholeMilliseconds)

        assertEquals(90, preview(draft()).next.first().minutes)
    }
}
