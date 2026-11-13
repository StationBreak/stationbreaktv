// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A grown-up starter and the grown-up leftovers drop what is made for kids (ADR-0020, grown-up-hold-2); a custom
 * channel does not. Each test has a TV-Y7 item and a PG item, both matching a genre.
 */
class LineupDaysGrownUpHoldTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }

    private val childrensItem = id(1)
    private val pgItem = id(2)

    private fun seed() {
        library.seed(movie(1, "Drama", rating = "TV-Y7"), movie(2, "Drama", rating = "PG"))
    }

    private fun days(lineup: Lineup): Map<String, ChannelDay> =
        runBlocking {
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
        }

    private fun ChannelDay.ids(): Set<CanonicalId> = pool.mapTo(HashSet()) { it.id }

    private fun drama() = rules("Drama", Source.Genre("Drama"))

    private fun starterAt(
        key: String,
        number: Int,
        edited: Boolean = false,
    ): LineupChannel {
        val source = ChannelSource.Starter(key, edited, drama())
        return channel(LineupIds.starter(key).value, number, ChannelState.On, source)
    }

    @Test
    fun aGrownUpStarterDropsTheChildrensRatedItem() {
        seed()
        val plain = starterAt("drama", 150)
        val edited = starterAt("drama-two", 160, edited = true)
        val days = days(lineupOf(plain, edited))
        assertEquals(setOf(pgItem), days.getValue(plain.id.value).ids())
        assertEquals(setOf(pgItem), days.getValue(edited.id.value).ids())
    }

    // grown-up-hold-3 (ADR-0026): the starter's real path drops a kid studio spelled with U+3164 between its words.
    @Test
    fun aGrownUpStarterDropsAKidStudioSpelledWithAHangulFiller() {
        library.seed(
            movie(1, "Drama", rating = "PG").copy(studios = listOf("Cartoon\u3164Network")),
            movie(2, "Drama", rating = "PG").copy(studios = listOf("Other Co")),
        )
        val drama = starterAt("drama", 150)
        val custom = channel("custom-out", 160, ChannelState.On, ChannelSource.Custom(drama()))
        val days = days(lineupOf(drama, custom))
        assertEquals(setOf(pgItem), days.getValue(drama.id.value).ids())
        assertEquals(setOf(childrensItem, pgItem), days.getValue("custom-out").ids())
    }

    @Test
    fun aCustomChannelStillAirsTheChildrensRatedItem() {
        seed()
        val custom = channel("custom-out", 150, ChannelState.On, ChannelSource.Custom(drama()))
        assertEquals(setOf(childrensItem, pgItem), days(lineupOf(custom)).getValue("custom-out").ids())
    }

    @Test
    fun aGrownUpStarterWhoseOnlyMatchesAreChildrensRatedIsChildrensRatingsOnlyNotNoMatches() {
        library.seed(movie(1, "Drama", rating = "TV-Y"))
        val drama = starterAt("drama", 150)
        assertEquals(HiddenReason.ChildrensRatingsOnly, days(lineupOf(drama)).getValue(drama.id.value).hidden)
    }

    @Test
    fun withNoKidsLeftoversTheGrownUpLeftoversKeepsWhatTheGrownUpStarterDropped() {
        seed()
        val drama = starterAt("drama", 150)
        val odds = leftovers("odds-and-ends", 199, null)
        assertEquals(setOf(childrensItem), days(lineupOf(drama, odds)).getValue(odds.id.value).ids())
    }

    @Test
    fun withKidsLeftoversOnWhatTheGrownUpStarterDroppedAirsThereNotOnTheGrownUpLeftovers() {
        seed()
        val kids = leftovers("kids-odds-and-ends", 99, "PG")
        val odds = leftovers("odds-and-ends", 199, null)
        val days = days(lineupOf(starterAt("drama", 150), kids, odds))
        assertEquals(setOf(childrensItem), days.getValue(kids.id.value).ids())
        assertEquals(emptySet(), days.getValue(odds.id.value).ids())
    }

    @Test
    fun airingNowhereIsZeroWithBothLeftoversOn() =
        runBlocking<Unit> {
            seed()
            val lineup =
                lineupOf(
                    starterAt("drama", 150),
                    leftovers("kids-odds-and-ends", 99, "PG"),
                    leftovers("odds-and-ends", 199, null),
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
        }

    // A TV-PG episode of a TV-Y7 series: made for kids by its series, but above the kid table's PG cap.
    private fun seedMixed() {
        val show = id(0x90)
        library.seedSeries(RuleSeries(show, "TV-Y7", listOf("Drama"), emptyList()))
        val tvPg = movie(3, "Drama", rating = "TV-PG")
        library.seed(
            movie(1, "Drama", rating = "TV-Y"),
            tvPg.copy(item = tvPg.item.copy(kind = ScheduleItemKind.Episode, seriesId = show)),
        )
    }

    @Test
    fun withKidsLeftoversOffTheGrownUpLeftoversAirsWhatTheStarterDropped() =
        runBlocking<Unit> {
            seed()
            val lineup = lineupOf(starterAt("drama", 150), leftovers("odds-and-ends", 199, null))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            val odds =
                source
                    .channelDays(ACCOUNT, LIBRARIES, NOON)
                    .first { it.channel.id == LineupIds.leftovers("odds-and-ends") }
            assertEquals(setOf(childrensItem), odds.ids())
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun withBothLeftoversOnAChildrensRatedItemLandsOnTheKidSideAndTheTableRefusedOneStaysOnTheGrownUpStarter() {
        seedMixed()
        val kids = leftovers("kids-odds-and-ends", 99, "PG")
        val odds = leftovers("odds-and-ends", 199, null)
        val days = days(lineupOf(starterAt("drama", 150), kids, odds))
        // Made for kids by its series, but the kid table refuses it: the grown-up starter keeps it (option C).
        assertEquals(setOf(id(3)), days.getValue(LineupIds.starter("drama").value).ids())
        assertEquals(setOf(id(1)), days.getValue(kids.id.value).ids())
        assertEquals(emptySet(), days.getValue(odds.id.value).ids())
    }

    @Test
    fun aTitleTheKidTableRefusesDoesNotAirNowhereAndIsNotTurnedAway() =
        runBlocking<Unit> {
            seedMixed()
            val lineup =
                lineupOf(
                    starterAt("drama", 150),
                    leftovers("kids-odds-and-ends", 99, "PG"),
                    leftovers("odds-and-ends", 199, null),
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    // An unrated animated family film and a TV-PG film from a kid studio: made for kids, and no kid channel can take
    // either. The grown-up channels keep them, so nothing airs nowhere.
    @Test
    fun aLibraryOfTitlesNoKidChannelCanTakeAirsOnGrownUpChannelsAndNowhereIsZero() =
        runBlocking<Unit> {
            library.seed(
                movie(1, "Drama", "Animation", "Family", rating = null),
                movie(2, "Drama", rating = "TV-PG").copy(studios = listOf("Pixar")),
            )
            val drama = starterAt("drama", 150)
            val kids = leftovers("kids-odds-and-ends", 99, "PG")
            val odds = leftovers("odds-and-ends", 199, null)
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(drama, kids, odds)))
            service.refresh(ACCOUNT, LIBRARIES)
            val days = source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
            assertEquals(setOf(id(1), id(2)), days.getValue(drama.id.value).ids())
            assertEquals(emptySet(), days.getValue(kids.id.value).ids())
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun anUnratedAnimatedFamilyTitleNoStarterMatchesAirsNowhereWhenTheGrownUpLeftoversIsOff() =
        runBlocking<Unit> {
            library.seed(movie(1, "Animation", "Family", rating = null))
            val lineup = lineupOf(starterAt("drama", 150), leftovers("kids-odds-and-ends", 99, "PG"))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            // No kid channel can take it, so it is not a title a grown-up pool drops: it counts as airing nowhere.
            assertEquals(1, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun withKidsOddsAndEndsOffAKidFilmNoKidStarterMatchesAirsOnTheGrownUpOddsAndEnds() =
        runBlocking<Unit> {
            library.seed(movie(1, "Kids", rating = "G"))
            val drama = starterAt("drama", 150)
            val odds = leftovers("odds-and-ends", 199, null)
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(drama, odds)))
            service.refresh(ACCOUNT, LIBRARIES)
            val days = source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
            assertEquals(setOf(id(1)), days.getValue(odds.id.value).ids())
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun withKidsOddsAndEndsOnTheSameFilmAirsThereAndNotOnTheGrownUpOddsAndEnds() =
        runBlocking<Unit> {
            library.seed(movie(1, "Kids", rating = "G"))
            val kids = leftovers("kids-odds-and-ends", 99, "PG")
            val odds = leftovers("odds-and-ends", 199, null)
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(starterAt("drama", 150), kids, odds)))
            service.refresh(ACCOUNT, LIBRARIES)
            val days = source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
            assertEquals(setOf(id(1)), days.getValue(kids.id.value).ids())
            assertEquals(emptySet(), days.getValue(odds.id.value).ids())
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(0, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun theGrownUpLeftoversPlayKeyChangesWithKidsOddsAndEnds() {
        val odds = leftovers("odds-and-ends", 199, null)
        val kidsOn = leftovers("kids-odds-and-ends", 99, "PG")
        val kidsOff = leftovers("kids-odds-and-ends", 99, "PG", ChannelState.Shelf)
        val on = LineupDays(TestCatalog).playKeyOf(odds, lineupOf(kidsOn, odds), NOON.toEpochMilliseconds())
        val off = LineupDays(TestCatalog).playKeyOf(odds, lineupOf(kidsOff, odds), NOON.toEpochMilliseconds())
        assertNotEquals(on, off)
        assertEquals(on, LineupDays(TestCatalog).playKeyOf(odds, lineupOf(kidsOn, odds), NOON.toEpochMilliseconds()))
    }

    @Test
    fun withBothLeftoversOffAKidFilmNoKidStarterMatchesIsTurnedAwayNotAiringNowhere() =
        runBlocking<Unit> {
            library.seed(movie(1, "Kids", rating = "G"))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(starterAt("drama", 150))))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
            assertEquals(1, service.turnedAwayByBothHoldsCount(ACCOUNT, LIBRARIES, NOON))
        }
}
