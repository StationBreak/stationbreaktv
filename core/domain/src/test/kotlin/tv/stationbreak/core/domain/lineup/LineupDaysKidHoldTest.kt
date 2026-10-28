// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A kid channel airs made-for-kids content only, on every path (spec 18): ruled days, leftovers, and what a hidden or
 * opt-in channel withholds. Each test has a kids' item (G, a kids genre) and a Fantasy-only item (PG), both matching.
 */
class LineupDaysKidHoldTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }

    private val kidsItem = id(1)
    private val fantasyItem = id(2)

    private fun seed() {
        library.seed(movie(1, "Animation", "Kids", rating = "G"), movie(2, "Animation", "Fantasy", rating = "PG"))
    }

    private fun days(lineup: Lineup): Map<String, ChannelDay> =
        runBlocking {
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
        }

    private fun ChannelDay.ids(): Set<CanonicalId> = pool.mapTo(HashSet()) { it.id }

    private fun animation(max: String? = null) = rules("Toons", Source.Genre("Animation"), maxRating = max)

    private fun starterAt(
        key: String,
        number: Int,
        rules: ChannelRules,
        edited: Boolean = false,
        state: ChannelState = ChannelState.On,
    ): LineupChannel = channel(LineupIds.starter(key).value, number, state, ChannelSource.Starter(key, edited, rules))

    private fun custom(
        id: String,
        number: Int,
    ) = channel(id, number, ChannelState.On, ChannelSource.Custom(animation()))

    @Test
    fun anUneditedKidStarterAirsOnlyTheKidsItem() {
        seed()
        val toons = starterAt("toons", 10, animation("G"))
        assertEquals(setOf(kidsItem), days(lineupOf(toons)).getValue(toons.id.value).ids())
    }

    @Test
    fun anEditedKidStarterWithNoCeilingIsStillHeld() {
        seed()
        val toons = starterAt("toons", 10, animation(null), edited = true)
        assertEquals(setOf(kidsItem), days(lineupOf(toons)).getValue(toons.id.value).ids())
    }

    @Test
    fun aCustomChannelIsHeldInTheKidsBandAndNotOutsideIt() {
        seed()
        val inBand = custom("custom-in", 20)
        val outside = custom("custom-out", 150)
        val days = days(lineupOf(inBand, outside))
        assertEquals(setOf(kidsItem), days.getValue("custom-in").ids())
        assertEquals(setOf(kidsItem, fantasyItem), days.getValue("custom-out").ids())
    }

    @Test
    fun aStoredKidKeyRenumberedOutOfTheBandIsStillHeld() {
        seed()
        val toons = starterAt("toons", 150, animation(), edited = true)
        val littleFarm = starterAt("little-farm", 160, animation(), edited = true)
        val days = days(lineupOf(toons, littleFarm))
        // "toons" is not a real kid key; "little-farm" is, and is not in TestCatalog (retired stand-in).
        assertEquals(setOf(kidsItem, fantasyItem), days.getValue(toons.id.value).ids())
        assertEquals(setOf(kidsItem), days.getValue(littleFarm.id.value).ids())
    }

    @Test
    fun kidsLeftoversHoldsOutGrownUpContentAndOddsAndEndsAirsIt() {
        seed()
        val kids = leftovers("kids-odds-and-ends", 99, "PG")
        val odds = leftovers("odds-and-ends", 199, null)
        val days = days(lineupOf(kids, odds))
        assertEquals(setOf(kidsItem), days.getValue(kids.id.value).ids())
        assertEquals(setOf(fantasyItem), days.getValue(odds.id.value).ids())
    }

    @Test
    fun aHiddenKidChannelWithholdsOnlyWhatItWouldAirIfShown() {
        seed()
        val hidden = starterAt("toons", 10, animation("G"), state = ChannelState.UserHidden)
        val kids = leftovers("kids-odds-and-ends", 99, "PG")
        val odds = leftovers("odds-and-ends", 199, null)
        val days = days(lineupOf(hidden, kids, odds))
        assertEquals(emptySet(), days.getValue(kids.id.value).ids(), "the kids' match is withheld")
        assertEquals(setOf(fantasyItem), days.getValue(odds.id.value).ids(), "the grown-up match reaches Odds")
    }

    @Test
    fun aHeldAndAnUnheldChannelWithTheSameRulesAtTheSameCutoffGetDifferentPools() {
        seed()
        // Same sources, exclusions and ceiling, so only the held flag tells their pools apart.
        val held = channel("custom-in", 20, ChannelState.On, ChannelSource.Custom(animation("PG")))
        val open = channel("custom-out", 150, ChannelState.On, ChannelSource.Custom(animation("PG")))
        val days = days(lineupOf(held, open))
        assertEquals(setOf(kidsItem), days.getValue("custom-in").ids())
        assertEquals(setOf(kidsItem, fantasyItem), days.getValue("custom-out").ids())
    }

    @Test
    fun aKidChannelWhoseMatchesAreAllGrownUpIsHiddenAsNothingMadeForKids() {
        library.seed(movie(2, "Animation", "Fantasy", rating = "PG"))
        val toons = starterAt("toons", 10, animation("G"))
        assertEquals(HiddenReason.NothingMadeForKids, days(lineupOf(toons)).getValue(toons.id.value).hidden)
    }

    @Test
    fun nothingAiringNowhereWithBothLeftoversOn() =
        runBlocking<Unit> {
            seed()
            val lineup =
                lineupOf(
                    starterAt("toons", 10, animation("G")),
                    leftovers("kids-odds-and-ends", 99, "PG"),
                    leftovers("odds-and-ends", 199, null),
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
        }
}
