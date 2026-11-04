// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.domain.lineup.CuratedLibrary.channel
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDay
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.curatedOn
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The day build and the planner read one "on the air on day d" rule (PR 5 final round, items 1 and 2): the engine's
 * `startsAfter`, `leavesBy` and `isCuratedOn`, decided by the day. So the curated on-set the day build homes over is
 * the one the planner protects and keeps in the basis, every lookup of one day agrees, and a play key holds only what
 * changes the day played.
 */
class SharedDayRuleTest {
    private val lib = CuratedLibrary
    private val noon = NOON.toEpochMilliseconds()
    private val days = LineupDays(TestCatalog)
    private val recipeKeys = TestCatalog.recipes.mapTo(HashSet()) { it.key }
    private val tomorrowNoon = noon + CuratedLibrary.DAY_MS

    private fun Lineup.with(
        id: ChannelId,
        change: (LineupChannel) -> LineupChannel,
    ): Lineup = copy(channels = channels.map { if (it.id == id) change(it) else it })

    // Whodunit leaves at the boundary (the planner left it out); westerns starts there (the planner picked it).
    private fun changing(): Lineup =
        lib
            .lineup()
            .with(lib.whodunitId) { it.copy(leavesAtMs = boundaryMs, shelfReason = ShelfReason.BelowCap) }
            .with(lib.westernsId) { it.copy(onSinceMs = boundaryMs) }

    // The curated starters the day build homes over on the day that holds [atMs] (no basis in these lineups).
    private fun dayBuildOnSet(
        lineup: Lineup,
        atMs: Long,
    ): Set<ChannelId> {
        val scheduled = days.scheduled(lineup, atMs, null)
        val curated = scheduled.filter { it.channel.source is ChannelSource.Starter && it.curation != null }
        val sets = scheduled.mapNotNull { it.curation?.on?.toSet() }.distinct()
        // Every reader of the day (curated starters and leftovers) has the one on-set: the scheduled curated starters.
        assertEquals(listOf(curated.map { it.channel.id }.toSet()), sets.ifEmpty { listOf(emptySet()) })
        return curated.map { it.channel.id }.toSet()
    }

    @Test
    fun theDayBuildAndThePlannerAgreeOnTodaysAndTomorrowsOnSet() {
        for (lineup in listOf(lib.lineup(), changing(), lib.lineup(westernsState = ChannelState.Shelf))) {
            val today = lineup.curatedOn(LineupDay.endingAt(boundaryMs), recipeKeys)
            val tomorrow = lineup.curatedOn(LineupDay.startingAt(boundaryMs), recipeKeys)
            assertEquals(today, dayBuildOnSet(lineup, noon), "today")
            assertEquals(today, dayBuildOnSet(lineup, boundaryMs - 1), "the last moment of today")
            assertEquals(tomorrow, dayBuildOnSet(lineup, boundaryMs), "tomorrow, before the roll")
            assertEquals(tomorrow, dayBuildOnSet(lineup, tomorrowNoon), "tomorrow, before the roll")
        }
        val changing = changing()
        assertEquals(
            setOf(lib.laughsId, lib.whodunitId),
            changing.curatedOn(LineupDay.endingAt(boundaryMs), recipeKeys),
            "the leaving channel airs today; the starting one does not",
        )
        assertEquals(
            setOf(lib.laughsId, lib.westernsId),
            changing.curatedOn(LineupDay.startingAt(boundaryMs), recipeKeys),
        )
    }

    // The guide asks at the half-hour before now: a leaving channel's day is the same at either moment, as a starting
    // one's is (StartingDayTest).
    @Test
    fun theGuidesHalfHourLookupIsTheLookupAtNowForALeavingChannel() {
        val lineup = changing()
        val origin = noon
        val now = noon + 20 * 60_000L
        for (at in listOf(origin, boundaryMs - 30 * 60_000L)) {
            val asked = days.curatedDays(lineup, at)
            val atNow = days.curatedDays(lineup, now)
            assertEquals(atNow.map { it.channel.id }, asked.map { it.channel.id })
            for (day in atNow) assertEquals(day.aired(), asked.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
            assertEquals(days.scheduled(lineup, now, null).map { it.n }, days.scheduled(lineup, at, null).map { it.n })
        }
        assertTrue(
            days
                .curatedDays(lineup, now)
                .dayOf(lib.whodunitId.value)
                .pool
                .isNotEmpty(),
            "it airs today",
        )
    }

    // A starter the planner leaves out at noon leaves at the boundary; the planner keeps today's on-set in the basis.
    // No key moves until the boundary: the leave changes no day before it (the swap simulation's eight leaving
    // channels).
    @Test
    fun aStarterGivenALeaveMidDayKeepsItsPlayKeyUntilTheBoundary() {
        val before = lib.lineup()
        val onToday = before.curatedOn(LineupDay.endingAt(boundaryMs), recipeKeys)
        val leaving =
            before
                .with(lib.whodunitId) { it.copy(leavesAtMs = boundaryMs, shelfReason = ShelfReason.BelowCap) }
                .copy(curationBasis = CurationBasis(boundaryMs, onToday, true))

        for (at in listOf(noon, boundaryMs - 1)) {
            assertEquals(days.playKeysOf(before, at), days.playKeysOf(leaving, at), "every key at $at")
        }
        // From the boundary (the roll shelves it) its key moves, and so do the curated starters' (their on-set did).
        val rolled = leaving.rolled(boundaryMs)
        assertEquals(ChannelState.Shelf, rolled.channel(lib.whodunitId.value).state)
        val after = days.playKeysOf(rolled, boundaryMs)
        val stayed = days.playKeysOf(before.rolled(boundaryMs), boundaryMs)
        assertNotEquals(stayed.getValue(lib.whodunitId), after.getValue(lib.whodunitId))
        assertNotEquals(stayed.getValue(lib.laughsId), after.getValue(lib.laughsId))
    }

    // A viewer's switch off and on again in the middle of a day moves no key: when it was switched on is no part of the
    // day played.
    @Test
    fun theMomentOfAMidDaySwitchOnIsNoPartOfThePlayKey() {
        val lineup = lib.lineup()
        val again = lineup.with(lib.laughsId) { it.copy(onSinceMs = noon) }
        assertEquals(days.playKeysOf(lineup, noon + 1), days.playKeysOf(again, noon + 1))
        // Its days on disk still differ (`inputsOf` keeps every input of every day).
        assertNotEquals(days.inputsOf(lineup, null), days.inputsOf(again, null))
    }
}
