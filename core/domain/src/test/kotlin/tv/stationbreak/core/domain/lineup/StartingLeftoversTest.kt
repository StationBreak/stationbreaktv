// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A leftovers channel that starts at a later boundary is a starting channel like any other (D1, ADR-0023 section 3):
 * its day today is empty (`HiddenReason.EmptyToday`) and it claims nothing, so the rest of the chain is what it would
 * be without it. At the boundary it airs and claims as usual (#620).
 */
class StartingLeftoversTest {
    private val lib = CuratedLibrary
    private val today = NOON.toEpochMilliseconds()
    private val tomorrow = today + CuratedLibrary.DAY_MS

    private val retired = "leftovers:retired-odds"
    private val odds = "leftovers:odds-and-ends"

    // A grown-up leftovers channel ahead of the odds-and-ends in the claim order (number 190).
    private fun withRetired(startsMs: Long): Lineup {
        val base = lib.lineup()
        return base.copy(channels = base.channels + leftovers("retired-odds", 190, null).copy(onSinceMs = startsMs))
    }

    private fun daysOf(
        lineup: Lineup,
        atMs: Long,
    ): List<ChannelDay> = LineupDays(TestCatalog).curatedDays(lineup, atMs)

    @Test
    fun aStartingLeftoversChannelIsEmptyTodayAndTheRestOfTheChainIsUntouched() {
        val starting = daysOf(withRetired(boundaryMs), today)
        val without = daysOf(lib.lineup(), today)

        val day = starting.dayOf(retired)
        assertTrue(day.pool.isEmpty(), "nothing airs before it starts")
        assertEquals(HiddenReason.EmptyToday, day.hidden)
        assertTrue(without.dayOf(odds).pool.isNotEmpty(), "the odds-and-ends has something to air without it")
        for (other in without) {
            assertEquals(other.aired(), starting.dayOf(other.channel.id.value).aired(), "${other.channel.id}")
        }
    }

    @Test
    fun aLeftoversChannelThatHasStartedAirsAndClaimsAsBefore() {
        val started = daysOf(withRetired(today - CuratedLibrary.DAY_MS), today)

        assertTrue(started.dayOf(retired).pool.isNotEmpty())
        assertTrue(started.dayOf(odds).pool.isEmpty(), "the channel before it in the order took everything")
    }

    @Test
    fun aStartingLeftoversChannelAirsAndClaimsFromItsBoundary() {
        val atBoundary = daysOf(withRetired(boundaryMs), tomorrow)

        assertTrue(atBoundary.dayOf(retired).pool.isNotEmpty(), "it airs tomorrow")
        assertTrue(atBoundary.dayOf(odds).pool.isEmpty(), "and claims first, as before it started")
    }

    // A made-for-kids item (animation with a kids genre, G): the kids leftovers channel takes it, the grown-up one only
    // while no kids leftovers channel is on the air.
    private val kidsItem = movie(1, "Animation", "Kids", rating = "G")

    private fun withKidsItem(
        lineup: Lineup,
        atMs: Long,
    ): List<ChannelDay> {
        val scheduled = LineupDays(TestCatalog).scheduled(lineup, atMs, null)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                lib.snapshotAt(it).let { s -> s.copy(items = s.items + kidsItem) }
            }
        return LineupDays(TestCatalog).days(lineup, scheduled, snapshots)
    }

    private fun kidsAndOdds(kidsStartsMs: Long): Lineup {
        val kids = leftovers("kids-odds-and-ends", 99, "PG").copy(onSinceMs = kidsStartsMs)
        return lineupOf(kids, leftovers("odds-and-ends", 199, null))
    }

    private val kids = LineupIds.leftovers("kids-odds-and-ends").value

    @Test
    fun aStartingKidsLeftoversChannelIsOffForTodaysHoldsSoOddsTakesTheKidItems() {
        val day = withKidsItem(kidsAndOdds(boundaryMs), today)

        assertEquals(HiddenReason.EmptyToday, day.dayOf(kids).hidden)
        assertTrue(day.dayOf(kids).pool.isEmpty())
        assertTrue(kidsItem.item.id in day.dayOf(odds).ids(), "nothing airs the kids' item today, so Odds does")
    }

    @Test
    fun aStartingKidsLeftoversChannelAirsTheKidItemsFromItsBoundaryAndOddsHoldsThemBack() {
        val day = withKidsItem(kidsAndOdds(boundaryMs), tomorrow)

        assertEquals(setOf(kidsItem.item.id), day.dayOf(kids).ids())
        assertTrue(kidsItem.item.id !in day.dayOf(odds).ids(), "back under the grown-up hold")
    }

    @Test
    fun aKidsLeftoversChannelThatHasStartedHoldsTheKidItemsAwayFromOdds() {
        val day = withKidsItem(kidsAndOdds(today - CuratedLibrary.DAY_MS), today)

        assertEquals(setOf(kidsItem.item.id), day.dayOf(kids).ids())
        assertTrue(kidsItem.item.id !in day.dayOf(odds).ids())
    }

    @Test
    fun theDayAwarePolicyIsWhatTheLeftoversPlayKeyReads() {
        val days = LineupDays(TestCatalog)
        val oddsId = ChannelId(LineupIds.leftovers("odds-and-ends").value)
        val starting = days.playKeysOf(kidsAndOdds(boundaryMs), today).getValue(oddsId)
        val started = days.playKeysOf(kidsAndOdds(today - CuratedLibrary.DAY_MS), today).getValue(oddsId)
        val tomorrowKey = days.playKeysOf(kidsAndOdds(boundaryMs), tomorrow).getValue(oddsId)

        assertTrue(starting != started, "Odds plays under Open today, GrownUp once the kids channel has started")
        assertEquals(started, tomorrowKey, "from the boundary it is the same policy as one that has started")
    }

    private fun ChannelDay.ids() = pool.mapTo(HashSet()) { it.id }
}
