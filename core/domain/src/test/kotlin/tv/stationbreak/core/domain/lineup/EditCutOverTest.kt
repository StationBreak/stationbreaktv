// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An edit never interrupts the airing item (spec 22, ruling 16): the schedule is stateless, so the day is laid out by
 * the new rules from the moment the edit is published, and what is on at the end of the airing item is the new rules'
 * block at the clock. Keeping the item being played is the live sitting's job (EditCutOverLiveTest).
 */
class EditCutOverTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val builds = mutableListOf<DayBuild>()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library, built = { builds += it }) { LIBRARIES }
    private val mine = ChannelId("custom:mine")

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private fun show(
        n: Int,
        name: String,
    ) {
        library.delegate.seedSeries(ACCOUNT, RuleSeries(id(n), "PG", listOf("Drama"), emptyList()))
        library.delegate.seedSeriesName(ACCOUNT, id(n), name)
    }

    private fun episode(
        n: Int,
        show: Int,
        number: Int,
        premiere: String,
    ) {
        val runtimeMs = 1_800_000L
        val kind = ScheduleItemKind.Episode
        val item = ScheduleItem(id(n), kind, runtimeMs, ADDED_MS, null, premiere, 2000, id(show), 1, number)
        library.delegate.seedRule(ACCOUNT, RuleItem(item, LIBRARY_ID, null, "PG", listOf("Drama"), emptyList()))
        val series = ItemId(id(show).value)
        val media = MediaItem(ItemId(id(n).value), "Episode $n", ItemKind.Episode, null, null, seriesId = series)
        library.delegate.seed(ACCOUNT, ItemId(LIBRARY_ID.value), media)
    }

    // Two shows of four episodes each; the older one's episodes have the lower numbers.
    private fun twoShows() {
        show(0x51, "Show One")
        show(0x52, "Show Two")
        (1..4).forEach { episode(0x2060 + it, 0x51, it, "2000-01-0$it") }
        (1..4).forEach { episode(0x2070 + it, 0x52, it, "2010-01-0$it") }
    }

    private fun rotation(): ChannelRules = twoShowRules(OrderingMode.Rotation)

    private fun inOrder(): ChannelRules = twoShowRules(OrderingMode.InOrder)

    private fun twoShowRules(mode: OrderingMode) =
        rules("Mine", Source.Series(id(0x51)), Source.Series(id(0x52)))
            .copy(formatVersion = 2, ordering = Ordering(mode))

    private fun custom(rules: ChannelRules) = channel(mine.value, 620, ChannelState.On, ChannelSource.Custom(rules))

    private fun holding(
        store: RecordingStore,
        vararg channels: LineupChannel,
    ) = runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels))) }

    private fun laughs() = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))

    private fun edit(change: LineupEdit) =
        runBlocking {
            val saved = service.edit(ACCOUNT, change)
            assertIs<EditOutcome.Applied>((saved as? Saved.Ok<*>)?.value, "the edit applied: $saved")
        }

    // The block on air at [at] on [channel]: item, start, end and where in the item it joins.
    private fun blockAt(
        source: BuiltInScheduleSource,
        channel: ChannelId,
        at: Instant,
    ): List<Any?> {
        val now = checkNotNull(runBlocking { source.onAir(channel, at, 3) }).now
        val program = now.airing as Airing.Program
        return listOf(program.itemId, now.startMs, now.endMs, program.mediaStartMs)
    }

    // #556: the guide goes by the channel summaries, and a new order or a reshuffle moves neither id nor number.
    private fun playKey(): Int =
        runBlocking { service.channels(ACCOUNT).first() }
            .first { it.id == mine }
            .playKey

    @Test
    fun theSummaryShowsAChangeOfPlayOrderAndAReshuffleButNotARename() {
        twoShows()
        holding(store, laughs(), custom(rotation()))
        val rotationKey = playKey()

        val shuffle = twoShowRules(OrderingMode.Shuffle)
        edit(LineupEdit.ReplaceCustom(mine, shuffle, number = null, kids = false))
        val shuffleKey = playKey()
        assertNotEquals(rotationKey, shuffleKey, "a new play order")

        edit(LineupEdit.Reseed(mine, Seed(7uL)))
        val reseededKey = playKey()
        assertNotEquals(shuffleKey, reseededKey, "a reshuffle")

        val renamed = shuffle.copy(name = "Renamed", seed = Seed(7uL))
        edit(LineupEdit.ReplaceCustom(mine, renamed, number = null, kids = false))
        assertEquals(reseededKey, playKey(), "a rename plays the same")
    }

    @Test
    fun theNextBlockAfterAnEditComesFromTheNewRules() {
        twoShows()
        holding(store, laughs(), custom(rotation()))
        val airing = checkNotNull(runBlocking { source.onAir(mine, NOON, 3) }).now
        // The moment the airing item ends.
        val end = Instant.fromEpochMilliseconds(airing.endMs)
        val rotationNext = blockAt(source, mine, end)

        edit(LineupEdit.ReplaceCustom(mine, inOrder(), number = null, kids = false))

        val afterEdit = blockAt(source, mine, end)
        // The same lineup built with In order from the start: the edit is nothing but the new rules.
        val fresh = RecordingStore()
        holding(fresh, laughs(), custom(inOrder()))
        val other = LineupService(fresh, library, clock, { 0 }, {}, TestCatalog)
        val expected = blockAt(BuiltInScheduleSource(other, library) { LIBRARIES }, mine, end)
        assertEquals(expected, afterEdit, "the new rules' block at the clock")
        assertNotEquals(rotationNext, afterEdit, "and not the old rules'")
    }

    // Per-channel reuse of built days (issue #548) is not there yet: an edit rebuilds every channel's day, so this
    // pins only that the rebuild does no more work than the first build.
    @Test
    fun otherChannelsKeepTheirContentAndTheLibraryIsNotReadAgain() {
        twoShows()
        library.seed(fillingLibrary())
        val others =
            listOf(
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
                leftovers("odds-and-ends", 199, null),
            )
        holding(store, *others.toTypedArray(), custom(rotation()))
        val before = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
        val reads = library.snapshotCutoffs.size

        edit(LineupEdit.ReplaceCustom(mine, inOrder(), number = null, kids = false))
        val after = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON) }

        fun pools(days: List<ChannelDay>) = days.associate { it.channel.id to it.pool.map { item -> item.id } }
        val was = pools(before)
        val now = pools(after)
        assertEquals(was.keys, now.keys)
        for (id in was.keys - mine) assertEquals(was.getValue(id), now.getValue(id), "the day of $id is unchanged")
        assertEquals(reads, library.snapshotCutoffs.size, "the snapshots are kept, not read again")
        assertEquals(2, builds.size, "the edit built the days once more")
        assertTrue(builds.last().evaluations <= builds.first().evaluations, "no more work than the first build")
    }

    @Test
    fun allFilteredOutIsTheReasonWhenFiltersRemoveEverything() {
        library.seed(fillingLibrary())
        val comedy = Source.Genre("Comedy")
        val onlyEpisodes = Filters(null, null, null, null, null, FilterKind.Episode)
        val filtered = rules("Mine", comedy).copy(formatVersion = 2, filters = onlyEpisodes)
        holding(store, laughs(), custom(filtered))

        val airings = runBlocking { source.airings(NOON) }

        val hidden = airings.first { it.id == mine }
        assertEquals(HiddenReason.AllFilteredOut, hidden.hidden)
        assertEquals(null, airings.first { it.number == 110 }.hidden)
    }
}
