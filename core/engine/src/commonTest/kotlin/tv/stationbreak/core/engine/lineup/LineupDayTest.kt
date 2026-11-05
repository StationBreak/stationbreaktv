// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared "on the air on day d" rule (PR 5 final round, item 1): decided by the day, never by the moment. */
class LineupDayTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)
    private val today = LineupDay.endingAt(boundary)
    private val tomorrow = LineupDay.startingAt(boundary)
    private val keys = setOf("alpha", "bravo")

    private val programmed =
        rules(listOf(Source.Genre("alpha"))).copy(formatVersion = 3, ordering = Ordering(OrderingMode.Curated))
    private val plain = rules(listOf(Source.Genre("alpha")))

    private fun starter(
        key: String,
        onSinceMs: Long = 0L,
        leavesAtMs: Long? = null,
        edited: Boolean = false,
        source: ChannelSource.Starter = ChannelSource.Starter(key, edited, programmed),
    ) = LineupChannel(LineupIds.starter(key), 110, null, null, ChannelState.On, source, onSinceMs, false, leavesAtMs)

    @Test
    fun theLineupsDaysMeetAtTheBoundary() {
        assertEquals(boundary, today.endMs)
        assertEquals(boundary, tomorrow.startMs)
        assertEquals(DAY_MS, today.endMs - today.startMs)
        assertEquals(boundary - DAY_MS, today.startMs)
    }

    @Test
    fun aSwitchOnForTheBoundaryStartsAfterTodayAndNotAfterTomorrow() {
        val planned = starter("alpha", onSinceMs = boundary)
        assertTrue(planned.startsAfter(today))
        assertFalse(planned.startsAfter(tomorrow))
        assertFalse(planned.isOnAirOn(today))
        assertTrue(planned.isOnAirOn(tomorrow))
        // A viewer's switch-on in the middle of today is on the air today, whatever moment of today is asked about.
        val viewers = starter("alpha", onSinceMs = noon + 60_000L)
        assertFalse(viewers.startsAfter(today))
        assertTrue(viewers.isOnAirOn(today))
    }

    @Test
    fun aChannelLeavingAtTheBoundaryIsOnTheAirTodayAndNotTomorrow() {
        val leaving = starter("alpha", leavesAtMs = boundary)
        assertFalse(leaving.leavesBy(today))
        assertTrue(leaving.leavesBy(tomorrow))
        assertTrue(leaving.isOnAirOn(today))
        assertFalse(leaving.isOnAirOn(tomorrow))
        assertFalse(starter("alpha").leavesBy(tomorrow), "one that does not leave")
    }

    @Test
    fun curatedOnADayFollowsTheRulesForThatDayAndTheCatalog() {
        assertTrue(starter("alpha").isCuratedOn(today.startMs, keys))
        assertFalse(starter("alpha", edited = true).isCuratedOn(today.startMs, keys), "an edited starter")
        assertFalse(starter("gone").isCuratedOn(today.startMs, keys), "a recipe no longer in the catalog")
        val plainStarter = starter("alpha", source = ChannelSource.Starter("alpha", false, plain))
        assertFalse(plainStarter.isCuratedOn(today.startMs, keys), "rules not in the curated order")
        // Programmed rules waiting for the boundary: curated from tomorrow only.
        val swapping = ChannelSource.Starter("alpha", false, programmed).withPrevious(plain, boundary)
        val waiting = starter("alpha", source = swapping)
        assertFalse(waiting.isCuratedOn(today.startMs, keys))
        assertTrue(waiting.isCuratedOn(tomorrow.startMs, keys))
    }

    @Test
    fun theCuratedOnSetOfADay() {
        val custom =
            LineupChannel(ChannelId("custom:1"), null, 610, null, ChannelState.On, ChannelSource.Custom(programmed), 0L)
        val lineup =
            Lineup(
                SERVER,
                USER,
                3,
                0,
                true,
                listOf(
                    starter("alpha", leavesAtMs = boundary),
                    starter("bravo", onSinceMs = boundary).copy(defaultNumber = 120),
                    custom,
                ),
            )
        assertEquals(setOf(LineupIds.starter("alpha")), lineup.curatedOn(today, keys))
        assertEquals(setOf(LineupIds.starter("bravo")), lineup.curatedOn(tomorrow, keys))
    }
}
