// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.TitleWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val HOUR_MS = 3_600_000L
private const val UNTIL = 123L

/** What a channel holds (#757): the tally of a day's pool, its lists and the "+ N more", over made-up titles. */
class ChannelHoldingsTest {
    private fun movie(
        n: Int,
        runtimeMs: Long,
    ) = ScheduleItem(id(n), ScheduleItemKind.Movie, runtimeMs, ADDED_MS, null, null, 2000, null, null, null)

    private fun episode(
        n: Int,
        series: CanonicalId,
        number: Int,
    ) = ScheduleItem(id(n), ScheduleItemKind.Episode, HOUR_MS / 2, ADDED_MS, null, null, 2001, series, 1, number)

    private fun film(
        n: Int,
        hours: Long = 2,
    ): ScheduleItem = movie(n, hours * HOUR_MS)

    private val named: TitleNames = { ids -> ids.associateWith { "Title ${it.value.takeLast(3)}" } }

    private fun held(
        pool: List<ScheduleItem>,
        weights: TitleWeights = TitleWeights.NONE,
        homed: Set<CanonicalId>? = null,
    ): ChannelHoldings? = runBlocking { HoldingsTally.of(pool, weights, homed)?.holdings(UNTIL, named) }

    private fun known(holdings: ChannelHoldings) = holdings.homes as HomeShows.Known

    @Test
    fun aDayThatAirsNothingHoldsNothing() {
        assertNull(held(emptyList()))
    }

    @Test
    fun homesListAnchorsFirstThenMostHoursThenTitleId() {
        val pool = listOf(film(0x10, 1), film(0x11, 5), film(0x12, 5), film(0x13, 1), film(0x14, 9))
        val weights = TitleWeights.of(mapOf(id(0x10) to 3, id(0x13) to 3, id(0x11) to 2, id(0x12) to 2, id(0x14) to 2))

        val holdings = checkNotNull(held(pool, weights, homed = pool.map { it.id }.toSet()))

        // The two anchors (equal hours: the lower ID first), then 9 h, then the 5 h pair by ID.
        val homes = known(holdings).titles
        assertEquals(listOf(0x10, 0x13, 0x14, 0x11, 0x12).map { id(it) }, homes.map { it.id })
        assertEquals(listOf(true, true, false, false, false), homes.map { it.anchor })
        assertEquals(HoldingsList.HomeTo, holdings.list)
        assertEquals(homes, holdings.listed)
        assertEquals(homes.map { it.name }, holdings.listedNames)
        assertEquals(9 * HOUR_MS, homes[2].ms)
    }

    @Test
    fun moreCountsTheHomesBeyondTheSixListed() {
        val pool = (1..37).map { film(0x100 + it) }
        val homed = pool.map { it.id }.toSet()

        val holdings = checkNotNull(held(pool, TitleWeights.of(homed.associateWith { 2 }), homed))

        assertEquals(6, known(holdings).titles.size)
        assertEquals(37, known(holdings).count)
        assertEquals(37, holdings.titleCount)
        assertEquals(31, holdings.more)
    }

    @Test
    fun noMoreWhenEveryTitleIsListed() {
        val pool = (1..6).map { film(0x100 + it) }
        val homed = pool.map { it.id }.toSet()

        assertEquals(0, checkNotNull(held(pool, TitleWeights.of(homed.associateWith { 2 }), homed)).more)
    }

    @Test
    fun guestsAreNeverListedAsHomesButTheyCountInTheTotalsLikeTheirHours() {
        val pool = listOf(film(0x20, 2), film(0x21, 2), film(0x22, 10), film(0x23, 4))
        // 0x22 is an anchor here and a guest: weight 3, homed elsewhere. 0x23 is a guest (weight 1).
        val weights = TitleWeights.of(mapOf(id(0x20) to 2, id(0x21) to 3, id(0x22) to 3))

        val holdings = checkNotNull(held(pool, weights, homed = setOf(id(0x20), id(0x21))))

        assertEquals(listOf(id(0x21), id(0x20)), known(holdings).titles.map { it.id })
        assertEquals(2, known(holdings).count)
        assertEquals(0, holdings.more)
        // The totals are of everything the channel airs: four titles, eighteen hours.
        assertEquals(4, holdings.titleCount)
        assertEquals(18 * HOUR_MS, holdings.totalMs)
        assertFalse(holdings.listed.any { it.id == id(0x22) || it.id == id(0x23) })
        assertEquals(listOf(id(0x22), id(0x23), id(0x20), id(0x21)), holdings.top.map { it.id })
    }

    @Test
    fun aDayWhoseHomesAreUnknownShowsTheTotalsAndNoListAtAll() {
        // Weights of 2 and 3 are not read as homes: a weight-3 title may be a guest anchor.
        val pool = listOf(film(0x30), film(0x31), film(0x32))
        val weights = TitleWeights.of(mapOf(id(0x30) to 3, id(0x31) to 2))

        val holdings = checkNotNull(held(pool, weights, homed = null))

        assertEquals(HomeShows.Unknown, holdings.homes)
        assertNull(holdings.list)
        assertTrue(holdings.listed.isEmpty())
        assertEquals(0, holdings.more)
        assertEquals(3, holdings.titleCount)
        assertEquals(6 * HOUR_MS, holdings.totalMs)
    }

    @Test
    fun aChannelKnownToHaveNoHomesIsMostlyItsSixBiggestTitles() {
        // A guest-only decade channel, a custom channel or the leftovers: nothing is homed here, and that is known.
        val pool = (1..9).map { film(0x200 + it, hours = it.toLong()) }

        val holdings = checkNotNull(held(pool, homed = emptySet()))

        assertEquals(HoldingsList.Mostly, holdings.list)
        assertEquals(HomeShows.Known(0, emptyList()), holdings.homes)
        assertEquals(listOf(9, 8, 7, 6, 5, 4).map { id(0x200 + it) }, holdings.listed.map { it.id })
        assertEquals(9, holdings.titleCount)
        assertEquals(3, holdings.more)
    }

    @Test
    fun hoursAreEveryTitlesEpisodesAdded() {
        val series = id(0x300)
        val episodes = (1..4).map { episode(0x300 + it, series, it) }

        val holdings = checkNotNull(held(episodes + film(0x400, 2), homed = emptySet()))

        assertEquals(2, holdings.titleCount)
        assertEquals(4 * HOUR_MS, holdings.totalMs)
        // The show and the film hold two hours each: the lower ID first.
        assertEquals(listOf(series, id(0x400)), holdings.top.map { it.id })
        assertEquals(2 * HOUR_MS, holdings.top.first().ms)
    }

    @Test
    fun daysShowFromTwoUpAndHoursNeverRoundToNothing() {
        fun hours(n: Long) = checkNotNull(held(listOf(film(1, n)))).let { it.hours to it.days }
        assertEquals(47L to null, hours(47))
        assertEquals(48L to 2L, hours(48))
        assertEquals(412L to 17L, hours(412))
        val short = checkNotNull(held(listOf(movie(2, 600_000L))))
        assertEquals(1L, short.hours)
        assertNull(short.days)
    }

    @Test
    fun theDayEndIsCarried() {
        assertEquals(UNTIL, checkNotNull(held(listOf(film(1)))).validUntilMs)
    }

    @Test
    fun aTitleWithNoNameIsPassedOverAndALookupThatFailsCostsOnlyTheNames() {
        val pool = (1..8).map { film(0x500 + it, hours = it.toLong()) }
        val tally = checkNotNull(HoldingsTally.of(pool, TitleWeights.NONE, emptySet()))
        val withoutTop = id(0x508)

        val passed = runBlocking { tally.holdings(UNTIL) { ids -> named(ids - withoutTop) } }
        assertEquals(listOf(7, 6, 5, 4, 3, 2).map { id(0x500 + it) }, passed.top.map { it.id })
        assertEquals(8, passed.titleCount)

        val failed = runBlocking { tally.holdings(UNTIL) { error("the library is out of reach") } }
        assertTrue(failed.top.isEmpty())
        assertEquals(8, failed.titleCount)
        assertEquals(36 * HOUR_MS, failed.totalMs)
    }

    // The same figures from the day the build made, through the schedule source (no second curation run).
    @Suppress("InjectDispatcher") // A test: the reader's work runs where the test runs.
    private class Rig(
        westernsState: ChannelState = ChannelState.On,
    ) {
        val library = CountingLibrary().apply { seed(CuratedLibrary.items) }
        val store = RecordingStore()
        val clock =
            object : kotlin.time.Clock {
                override fun now() = NOON
            }
        val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
        val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }
        var asked = 0

        // The library's title lookup, counted and with names made up from the IDs.
        private val naming =
            object : LibraryRepository by library {
                override suspend fun titleNames(
                    account: LibraryAccount,
                    ids: Set<CanonicalId>,
                ): Map<CanonicalId, String> {
                    asked++
                    return ids.associateWith { "Title ${it.value.takeLast(4)}" }
                }
            }
        val reader = BuiltInChannelHoldings(source, naming, Dispatchers.Unconfined)

        init {
            runBlocking {
                library.delegate.useAccount(ACCOUNT)
                store.inner.save(ACCOUNT, LineupCodec.encode(CuratedLibrary.lineup(westernsState)))
            }
        }

        fun read(id: String) = runBlocking { reader.read(ChannelId(id), NOON) }

        fun day(id: String) = days().firstOrNull { it.channel.id.value == id }

        private fun days() = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
    }

    @Test
    fun aCuratedStarterHoldsItsHomesAndItsTotalsCountItsGuests() {
        val rig = Rig()
        val day = checkNotNull(rig.day(CuratedLibrary.laughsId.value))
        val holdings = checkNotNull(rig.read(CuratedLibrary.laughsId.value))

        val homed = checkNotNull(day.homed)
        assertTrue(homed.isNotEmpty())
        assertEquals(day.pool.sumOf { it.runtimeMs }, holdings.totalMs)
        assertEquals(day.pool.mapTo(HashSet()) { it.groupId }.size, holdings.titleCount)
        assertEquals(homed.size, known(holdings).count)
        assertTrue(known(holdings).titles.all { it.id in homed })
        assertEquals(HoldingsList.HomeTo, holdings.list)
        assertEquals(day.epochEndMs, holdings.validUntilMs)
        // Anchors, then hours, then ID.
        val order = compareByDescending<HeldTitle> { it.anchor }.thenByDescending { it.ms }.thenBy { it.id.value }
        val homes = known(holdings).titles
        assertEquals(homes.sortedWith(order), homes)
    }

    @Test
    fun guestsOfAnotherChannelsHomeAreNotListedAsHomesAndTheirHoursAreCounted() {
        val rig = Rig()
        val withGuests =
            listOf(CuratedLibrary.laughsId, CuratedLibrary.whodunitId, CuratedLibrary.westernsId).filter { id ->
                val day = checkNotNull(rig.day(id.value))
                day.pool.mapTo(HashSet()) { it.groupId }.size > checkNotNull(day.homed).size
            }
        assertTrue(withGuests.isNotEmpty(), "the curated library has guests")
        for (id in withGuests) {
            val day = checkNotNull(rig.day(id.value))
            val holdings = checkNotNull(rig.read(id.value))
            assertTrue(known(holdings).titles.all { it.id in checkNotNull(day.homed) })
            assertEquals(day.pool.sumOf { it.runtimeMs }, holdings.totalMs)
            assertTrue(holdings.titleCount > known(holdings).count)
        }
    }

    @Test
    fun aCustomChannelIsKnownToHaveNoHomesAndIsMostlyItsBiggestTitles() {
        val rig = Rig()
        val holdings = checkNotNull(rig.read("custom-1"))

        assertEquals(HoldingsList.Mostly, holdings.list)
        assertEquals(0, known(holdings).count)
        assertEquals(CuratedLibrary.horror.size, holdings.titleCount)
        assertEquals(CuratedLibrary.horror.size, holdings.top.size)
    }

    @Test
    fun theLeftoversAreMostlyWhatNoChannelHomed() {
        val rig = Rig()
        val holdings = checkNotNull(rig.read("leftovers:odds-and-ends"))

        assertEquals(HoldingsList.Mostly, holdings.list)
        assertEquals(CuratedLibrary.documentary.size, holdings.titleCount)
        assertEquals(CuratedLibrary.documentary.sumOf { it.item.runtimeMs }, holdings.totalMs)
    }

    @Test
    fun aChannelThatIsOffHoldsNothing() {
        val rig = Rig(westernsState = ChannelState.UserHidden)

        assertNull(rig.read(CuratedLibrary.westernsId.value))
        assertNotNull(rig.read(CuratedLibrary.laughsId.value))
        assertNull(rig.read("custom-unknown"))
    }

    @Test
    fun theHoldingIsKeptForTheDayAndNamedOnce() {
        val rig = Rig()

        val first = rig.read(CuratedLibrary.laughsId.value)
        val asked = rig.asked
        val second = rig.read(CuratedLibrary.laughsId.value)

        assertSame(first, second)
        assertEquals(asked, rig.asked)
    }

    @Test
    fun aDaySavedAndRestoredHoldsTheSameAsBeforeTheSave() {
        // The restart: the days a process built, written to disk, and read by the next one.
        val days = LineupDays(TestCatalog)
        val noon = NOON.toEpochMilliseconds()
        val lineup = CuratedLibrary.lineup()
        val scheduled = days.scheduled(lineup, noon, null)
        val persisted = PersistedDays(MemoryDayStore(), "app-1", {}, PersistedDays.HOLDS)
        val key = persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, days.inputsOf(lineup, null), 1, scheduled)
        val built = days.curatedDays(lineup, noon)
        assertTrue(runBlocking { persisted.save(ACCOUNT, key, built) })

        val restored = checkNotNull(runBlocking { persisted.load(ACCOUNT, key, scheduled, days::restore) })

        assertEquals(built.map { it.channel.id }, restored.map { it.channel.id })
        for ((before, after) in built.zip(restored)) {
            assertEquals(before.homed, after.homed, "${before.channel.id}")
            assertEquals(holdingsOf(before), holdingsOf(after), "${before.channel.id}")
        }
        // A curated channel has homes, a custom one none: both known.
        assertTrue(restored.any { it.homed?.isNotEmpty() == true })
        assertEquals(emptySet(), restored.first { it.channel.id.value == "custom-1" }.homed)
    }

    @Test
    fun aDayFromAFormatSevenFileHasNoHomesAndSaysNothingOfThem() {
        val days = LineupDays(TestCatalog)
        val noon = NOON.toEpochMilliseconds()
        val lineup = CuratedLibrary.lineup()
        val scheduled = days.scheduled(lineup, noon, null)
        val store = MemoryDayStore()
        val persisted = PersistedDays(store, "app-1", {}, PersistedDays.HOLDS)
        val key = persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, days.inputsOf(lineup, null), 1, scheduled)
        // The weights a v7 file kept and a curated day reads back; the homes it never had.
        val keyOfFile = DaysV7Fixture.keyFor(key)
        store.files[ACCOUNT to PersistedDays.nameOf(keyOfFile)] = DaysV7Fixture.bytes

        val restored = checkNotNull(runBlocking { persisted.load(ACCOUNT, keyOfFile, scheduled, days::restore) })

        assertTrue(restored.all { it.homed == null })
        val laughs = restored.first { it.channel.id == CuratedLibrary.laughsId }
        assertTrue(laughs.weights.entries.isNotEmpty(), "the weights came back")
        val holdings = checkNotNull(holdingsOf(laughs))
        assertEquals(HomeShows.Unknown, holdings.homes)
        assertNull(holdings.list)
        assertEquals(laughs.pool.sumOf { it.runtimeMs }, holdings.totalMs)
        assertEquals(laughs.pool.mapTo(HashSet()) { it.groupId }.size, holdings.titleCount)
    }

    private fun holdingsOf(day: ChannelDay): ChannelHoldings? =
        runBlocking { HoldingsTally.of(day.pool, day.weights, day.homed)?.holdings(day.epochEndMs, named) }
}
