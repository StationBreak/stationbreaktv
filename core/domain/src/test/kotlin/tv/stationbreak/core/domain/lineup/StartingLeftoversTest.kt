// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.Lineup
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
}
