// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.lineup.builder.BuiltInChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The extras filter takes effect from the next broadcast-day start (spec 23, extras-1): an existing lineup keeps today
 * as it is, a new account filters from the start, and the planner and the previews always filter.
 */
class ExtrasWiringTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private var now = FOURTEEN
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val events = mutableListOf<LineupEvent>()
    private val service = LineupService(store, library, clock, { 0 }, { events += it }, TestCatalog)
    private val disk = MemoryDayStore()

    // A 90-second opening and a 3.5-hour episode, both Comedy: the laughs channel would air them.
    private val opening = id(900)
    private val tooLong = id(901)

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        library.seed(extra(900, 90_000L, "NCOP1"), extra(901, 12_600_000L, "Show A 12"))
    }

    private fun extra(
        n: Int,
        runtimeMs: Long,
        name: String,
    ) = RuleItem(
        item = ScheduleItem(id(n), ScheduleItemKind.Episode, runtimeMs, ADDED_MS, null, null, 2000, null, null, null),
        libraryId = LIBRARY_ID,
        seasonId = null,
        officialRating = "R",
        genres = listOf("Comedy"),
        tags = emptyList(),
        name = name,
    )

    private fun held() =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"), timing = backToBack)),
            leftovers("odds-and-ends", 199, null),
        )

    // As the planner writes it: a plan leaves a back-to-back channel's layout as it is.
    private val backToBack = Timing(null, false, null, null, null, null, BroadcastDay(0, "06:00"))

    private fun save(meta: RefreshMeta) = runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(held(), meta)) }

    private fun source(disk: PersistedDays? = null): BuiltInScheduleSource =
        BuiltInScheduleSource(service, library, persisted = disk) { selectionOf(LIBRARIES) }

    private fun daysAt(
        source: BuiltInScheduleSource,
        at: Instant,
    ) = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }

    private fun airedIds(days: List<ChannelDay>) = days.flatMap { d -> d.pool.map { it.id } }.toSet()

    private fun extras(): Set<CanonicalId> = setOf(opening, tooLong)

    // The laughs channel's blocks: the planner may add channels around it, which are not what is being compared.
    private fun blocksOf(days: List<ChannelDay>) = days.first { it.channel.id == laughs }.day.blocks

    private val laughs = LineupIds.starter("laughs")

    private fun meta(): RefreshMeta = checkNotNull(service.lineup(ACCOUNT).value).meta

    @Test
    fun theDaysNeverAirAnExtraOnceItApplies() {
        save(RefreshMeta(extrasFromMs = Long.MIN_VALUE))
        val filtered = source()
        assertTrue(airedIds(daysAt(filtered, FOURTEEN)).none { it in extras() }, "today")
        assertTrue(airedIds(daysAt(filtered, FOURTEEN + 1.days)).none { it in extras() }, "tomorrow")

        // The control: with no boundary yet (a file from before) the same library does air them.
        val freshStore = RecordingStore()
        runBlocking { freshStore.inner.save(ACCOUNT, LineupCodec.encode(held(), RefreshMeta())) }
        val fresh = LineupService(freshStore, library, clock, { 0 }, {}, TestCatalog)
        val unfiltered = BuiltInScheduleSource(fresh, library) { selectionOf(LIBRARIES) }
        assertTrue(airedIds(daysAt(unfiltered, FOURTEEN)).containsAll(extras()), "the control airs both")
    }

    @Test
    fun theExtrasFilterStartsAtTheNextBoundary() =
        runBlocking<Unit> {
            save(RefreshMeta())
            val before = daysAt(source(), FOURTEEN)
            assertTrue(airedIds(before).containsAll(extras()))

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(Instant.parse("2026-10-20T06:00:00Z").toEpochMilliseconds(), meta().extrasFromMs)
            val after = source()
            val today = daysAt(after, FOURTEEN)
            val tomorrow = daysAt(after, FOURTEEN + 1.days)
            assertEquals(blocksOf(before), blocksOf(today), "today's blocks are as they were")
            assertTrue(airedIds(today).containsAll(extras()), "today still airs them")
            assertTrue(airedIds(tomorrow).none { it in extras() }, "tomorrow leaves both out")
        }

    @Test
    fun aNewAccountFiltersFromTheStart() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(Long.MIN_VALUE, meta().extrasFromMs)
            assertTrue(airedIds(daysAt(source(), FOURTEEN)).none { it in extras() })
        }

    @Test
    fun theBoundaryIsKeptByLaterRefreshesAndEdits() =
        runBlocking<Unit> {
            save(RefreshMeta())
            service.refresh(ACCOUNT, LIBRARIES)
            val first = meta().extrasFromMs
            now = FOURTEEN + 3.days

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(first, meta().extrasFromMs, "set once, at the first plan")
        }

    private val nextSix = Instant.parse("2026-10-20T06:00:00Z").toEpochMilliseconds()

    @Test
    fun aStoredBoundaryFarInTheFutureIsRecomputed() =
        runBlocking<Unit> {
            save(RefreshMeta(extrasFromMs = FOURTEEN.toEpochMilliseconds() + 400L * 86_400_000L))

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(nextSix, meta().extrasFromMs)
        }

    @Test
    fun aNormalStoredBoundaryIsKept() =
        runBlocking<Unit> {
            val soon = nextSix
            save(RefreshMeta(extrasFromMs = soon))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(soon, meta().extrasFromMs)
        }

    @Test
    fun aResetRecomputesAFarFutureBoundary() =
        runBlocking<Unit> {
            save(RefreshMeta(extrasFromMs = FOURTEEN.toEpochMilliseconds() + 400L * 86_400_000L))

            service.reset(ACCOUNT, LIBRARIES)

            assertEquals(nextSix, meta().extrasFromMs)
        }

    @Test
    fun anUnreadableFileSetAsideIsNotANewAccount() =
        runBlocking<Unit> {
            store.inner.save(ACCOUNT, "not a lineup".encodeToByteArray())

            service.refresh(ACCOUNT, LIBRARIES)

            assertNotNull(store.inner.setAsideCopy(ACCOUNT), "the file was set aside")
            assertEquals(nextSix, meta().extrasFromMs, "a lineup was lost, not never made: today stays")
        }

    @Test
    fun thePlanRanksWithoutExtras() =
        runBlocking<Unit> {
            // Westerns hold 8 hours of films and three episodes over the limit (12 more hours): only those fill it.
            val library = CountingLibrary()
            library.delegate.useAccount(ACCOUNT)
            val noWesterns = fillingLibrary().filterNot { it.genres == listOf("Western") }
            library.seed(noWesterns)
            library.seed((0 until 4).map { movie(120 + it, "Western", rating = "R") })
            library.seed((0 until 3).map { extraEpisode(130 + it, "Western") })
            val planner = LineupService(RecordingStore(), library, clock, { 0 }, {}, TestCatalog)

            planner.refresh(ACCOUNT, LIBRARIES)

            val channels = checkNotNull(planner.lineup(ACCOUNT).value).lineup.channels
            assertEquals(ChannelState.On, channels.first { it.id == LineupIds.starter("laughs") }.state)
            assertNotEquals(
                ChannelState.On,
                channels.first { it.id == LineupIds.starter("westerns") }.state,
                "its hours are the extras'",
            )
        }

    private fun extraEpisode(
        n: Int,
        genre: String,
    ) = RuleItem(
        item = ScheduleItem(id(n), ScheduleItemKind.Episode, 14_400_000L, ADDED_MS, null, null, 2000, null, null, null),
        libraryId = LIBRARY_ID,
        seasonId = null,
        officialRating = "R",
        genres = listOf(genre),
        tags = emptyList(),
        name = "Show B $n",
    )

    @Test
    fun thePreviewLeavesExtrasOut() =
        runBlocking<Unit> {
            save(RefreshMeta())
            val shared = SharedSnapshots(CoroutineScope(SupervisorJob()))
            val previews = BuiltInChannelPreviews(service, library, shared) { LIBRARIES }
            val draft =
                ChannelDraft(
                    "Mine",
                    listOf(DraftSource.Genre("Comedy", "Comedy")),
                    emptyList(),
                    DraftFilters(),
                    kids = false,
                    seed = Seed(11uL),
                )

            val shown = previews.preview(ACCOUNT, draft, FOURTEEN, null)

            val ready = shown as ChannelPreview.Ready
            assertEquals(7, ready.itemCount, "the seven films; the opening and the long episode are out")
        }

    @Test
    fun aMissNamesTheChangedVersionList() {
        assertTrue(ExtrasFilter.VERSION_NAME in PersistedDays.HOLDS)
        save(RefreshMeta(extrasFromMs = Long.MIN_VALUE))
        val old = PersistedDays(disk, "1:100", {}, PersistedDays.HOLDS - ExtrasFilter.VERSION_NAME)
        val first = source(old)
        daysAt(first, FOURTEEN)
        runBlocking { first.awaitSaves() }
        assertTrue(disk.files.isNotEmpty(), "the old build left a day file")

        val reasons = mutableListOf<DaySnapshotEvent>()
        val next = source(PersistedDays(disk, "1:100", { reasons += it }))
        daysAt(next, FOURTEEN)

        val miss = reasons.filterIsInstance<DaySnapshotEvent.Miss>().last()
        assertEquals(DaySnapshotMiss.Holds, miss.reason, "the file was saved without extras-1 in its key")
    }

    @Test
    fun theBoundaryIsAnInputOfTheDays() {
        val days = LineupDays(TestCatalog)
        val none = days.inputsOf(held(), null)
        val some = days.inputsOf(held(), 1_000L)

        assertNotEquals(none, some)
        assertNotEquals(some, days.inputsOf(held(), 2_000L))
        assertEquals(some, days.inputsOf(held(), 1_000L))
    }

    @Test
    fun aFilteredAndAnUnfilteredDayWithTheSameCutoffNeverSharePools() {
        // Two channels with the same sources and settings, one filtered and one not, share a cut-off.
        val comedy = Source.Genre("Comedy")
        val a = channel("a", 600, ChannelState.On, ChannelSource.Custom(rules("A", comedy)))
        val b = channel("b", 610, ChannelState.On, ChannelSource.Custom(rules("A", comedy)))
        val lineup = lineupOf(a, b)
        val days = LineupDays(TestCatalog)
        val atMs = FOURTEEN.toEpochMilliseconds()
        val plain = days.scheduled(lineup, atMs, null)
        // One day filtered and one not, as a boundary between their starts would make them.
        val scheduled =
            listOf(
                Scheduled(plain[0].channel, plain[0].settings, plain[0].n, extras = true),
                Scheduled(plain[1].channel, plain[1].settings, plain[1].n, extras = false),
            )
        assertEquals(scheduled[0].cutoffMs, scheduled[1].cutoffMs)
        val raw = snapshotOf(scheduled[0].cutoffMs, listOf(movie(1, "Comedy"), extra(900, 90_000L, "NCOP1")))
        val filtered = ExtrasFilter.filter(raw).snapshot
        var evaluations = 0

        val built =
            days.days(
                lineup,
                scheduled,
                mapOf(raw.cutoffMs to raw),
                filteredSnapshots = mapOf(raw.cutoffMs to filtered),
                evaluated = { evaluations++ },
            )

        val pools = built.associate { it.channel.id.value to it.pool.map { item -> item.id } }
        assertEquals(listOf(id(1)), pools.getValue("a"))
        assertEquals(listOf(id(1), opening), pools.getValue("b").sortedBy { it.value })
        assertEquals(2, evaluations, "one evaluation for each flag")
    }

    private fun snapshotOf(
        cutoff: Long,
        items: List<RuleItem>,
    ) = RuleSnapshot(cutoff, setOf(LIBRARY_ID), items.sortedBy { it.item.id.value }, emptyMap(), emptyMap(), true)

    @Test
    fun filteredAndPlainSnapshotsAreKeptApart() =
        runBlocking<Unit> {
            val cache = SnapshotCache(CoroutineScope(SupervisorJob()))
            val plain = SnapshotCache.Key(ACCOUNT, LIBRARIES, 5L, 1L, extras = false)
            val filtered = plain.copy(extras = true)
            val rawSnapshot = snapshotOf(5L, listOf(movie(1, "Comedy")))
            val otherSnapshot = snapshotOf(5L, emptyList())

            val one = cache.get(plain) { rawSnapshot }
            val two = cache.get(filtered) { otherSnapshot }

            assertTrue(one.snapshot === rawSnapshot)
            assertTrue(two.snapshot === otherSnapshot, "the filtered key did not find the plain snapshot")
            assertTrue(cache.holdsAll(listOf(plain, filtered)))
        }

    @Test
    fun theNextBoundaryIsTheNextDayStart() {
        val lineup = held()
        val nowMs = FOURTEEN.toEpochMilliseconds()
        val sixTomorrow = Instant.parse("2026-10-20T06:00:00Z").toEpochMilliseconds()
        assertEquals(sixTomorrow, service.nextBoundary(lineup, nowMs))
        val fiveAm = Instant.parse("2026-10-19T05:00:00Z").toEpochMilliseconds()
        assertEquals(Instant.parse("2026-10-19T06:00:00Z").toEpochMilliseconds(), service.nextBoundary(lineup, fiveAm))
        val atSix = Instant.parse("2026-10-19T06:00:00Z").toEpochMilliseconds()
        assertEquals(sixTomorrow, service.nextBoundary(lineup, atSix), "a day that has begun is not the next one")
    }

    @Test
    fun theNextBoundaryFollowsTheLineupsOffsetElseTheStandardOne() {
        // 06:00 at UTC-5 is 11:00Z.
        val west = lineupOf(leftovers("odds-and-ends", 199, null), offset = -300)
        val nowMs = FOURTEEN.toEpochMilliseconds()
        assertEquals(Instant.parse("2026-10-20T11:00:00Z").toEpochMilliseconds(), service.nextBoundary(west, nowMs))
        val standard = LineupService(store, library, clock, { 60 }, {}, TestCatalog)
        assertEquals(Instant.parse("2026-10-20T05:00:00Z").toEpochMilliseconds(), standard.nextBoundary(null, nowMs))
    }

    // The upgrade copy: the old file's bytes are kept once, before the first save of the new version.

    private class CopyStore(
        val inner: InMemoryLineupStore = InMemoryLineupStore(),
    ) : tv.stationbreak.core.domain.LineupStore by inner {
        val calls = mutableListOf<String>()
        var failCopy = false

        override suspend fun save(
            account: tv.stationbreak.core.domain.LibraryAccount,
            bytes: ByteArray,
        ): Boolean {
            calls += "save"
            return inner.save(account, bytes)
        }

        override suspend fun keepCopy(
            account: tv.stationbreak.core.domain.LibraryAccount,
            tag: String,
        ): Boolean {
            calls += "keepCopy:$tag"
            return !failCopy && inner.keepCopy(account, tag)
        }
    }

    private fun versionOneBytes(): ByteArray =
        LineupCodec
            .encode(held())
            .decodeToString()
            .replace("\"version\":3", "\"version\":1")
            .encodeToByteArray()

    @Test
    fun anOlderFileIsCopiedOnceBeforeTheFirstSave() =
        runBlocking<Unit> {
            val files = CopyStore()
            val old = versionOneBytes()
            files.inner.save(ACCOUNT, old)
            val tv = LineupService(files, library, clock, { 0 }, { events += it }, TestCatalog)

            tv.refresh(ACCOUNT, LIBRARIES)
            tv.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("keepCopy:v1", "save", "save"), files.calls)
            assertContentEquals(old, files.inner.keptCopy(ACCOUNT, "v1"))
        }

    @Test
    fun aVersionTwoFileIsCopiedOnceUnderItsOwnTag() =
        runBlocking<Unit> {
            val files = CopyStore()
            val old =
                LineupCodec
                    .encode(held())
                    .decodeToString()
                    .replace("\"version\":3", "\"version\":2")
                    .encodeToByteArray()
            files.inner.save(ACCOUNT, old)
            val tv = LineupService(files, library, clock, { 0 }, { events += it }, TestCatalog)

            tv.refresh(ACCOUNT, LIBRARIES)
            tv.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("keepCopy:v2", "save", "save"), files.calls)
            assertContentEquals(old, files.inner.keptCopy(ACCOUNT, "v2"))
            assertEquals(null, files.inner.keptCopy(ACCOUNT, "v1"))
        }

    @Test
    fun aCurrentFileIsNotCopied() =
        runBlocking<Unit> {
            val files = CopyStore()
            files.inner.save(ACCOUNT, LineupCodec.encode(held()))
            val tv = LineupService(files, library, clock, { 0 }, { events += it }, TestCatalog)

            tv.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("save"), files.calls)
        }

    @Test
    fun aFailedCopyIsReportedAndTheSaveStillHappens() =
        runBlocking<Unit> {
            val files = CopyStore().apply { failCopy = true }
            files.inner.save(ACCOUNT, versionOneBytes())
            val tv = LineupService(files, library, clock, { 0 }, { events += it }, TestCatalog)

            tv.refresh(ACCOUNT, LIBRARIES)

            assertTrue(LineupEvent.BackupFailed in events)
            assertEquals(listOf("keepCopy:v1", "save"), files.calls)
        }

    @Test
    fun forgettingTheLineupDropsTheUpgradeCopy() =
        runBlocking<Unit> {
            val files = CopyStore()
            files.inner.save(ACCOUNT, versionOneBytes())
            val tv = LineupService(files, library, clock, { 0 }, {}, TestCatalog)
            tv.refresh(ACCOUNT, LIBRARIES)
            assertNotNull(files.inner.keptCopy(ACCOUNT, "v1"))

            tv.forget(ACCOUNT)

            assertNull(files.inner.keptCopy(ACCOUNT, "v1"))
        }

    private companion object {
        /** 2026-10-19T14:00:00Z, after the day start at 06:00. */
        val FOURTEEN: Instant = Instant.parse("2026-10-19T14:00:00Z")
    }
}
