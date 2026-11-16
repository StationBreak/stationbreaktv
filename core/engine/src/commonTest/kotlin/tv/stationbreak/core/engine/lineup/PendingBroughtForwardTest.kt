// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What waits for the next boundary is brought to today's start, for the debug rebuild only (#812). */
class PendingBroughtForwardTest {
    private val keys = listOf("alpha", "bravo", "charlie", "delta", "echo")
    private val base = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 110 + i * 10) })
    private val lineup: Lineup = planOf(null, libraryOf(base.recipes.associate { it.key to QUALIFYING_ITEMS }), base)

    private val boundary = 90_000L
    private val today = 4_000L
    private val elsewhere = 70_000L

    private fun Lineup.with(
        key: String,
        change: (LineupChannel) -> LineupChannel,
    ) = withChannel(key, change)

    @Test
    fun anUneditedStartersWaitingRulesMoveToTodaysStart() {
        val pending =
            lineup.with("alpha") {
                val old = it.starter.rules
                val changed = it.starter.copy(rules = old.copy(sources = listOf(Source.Genre("other"))))
                it.copy(source = changed.withPrevious(old, boundary))
            }

        val moved = pending.withPendingBroughtForward(boundary, today)

        assertEquals(today, moved.channel("alpha").starter.rulesFromMs)
        assertEquals(pending.channel("alpha").starter.previousRules, moved.channel("alpha").starter.previousRules)
    }

    @Test
    fun aStarterThePlannerLeavesOutLeavesAtTodaysStart() {
        val pending = lineup.with("bravo") { it.copy(leavesAtMs = boundary, shelfReason = ShelfReason.BelowCap) }

        assertEquals(today, pending.withPendingBroughtForward(boundary, today).channel("bravo").leavesAtMs)
    }

    @Test
    fun aChannelThePlannerSwitchedOnForTheBoundaryIsOnSinceTodaysStart() {
        val pending = lineup.with("charlie") { it.copy(onSinceMs = boundary, viewerChoice = ViewerChoice.None) }

        assertEquals(today, pending.withPendingBroughtForward(boundary, today).channel("charlie").onSinceMs)
    }

    @Test
    fun aBasisForTheBoundaryIsDropped() {
        val pending = lineup.copy(curationBasis = CurationBasis(boundary, setOf(ChannelId("starter:alpha")), true))

        assertNull(pending.withPendingBroughtForward(boundary, today).curationBasis)
    }

    @Test
    fun aBasisForAnotherTimeStays() {
        val other = lineup.copy(curationBasis = CurationBasis(elsewhere, setOf(ChannelId("starter:alpha")), true))

        assertSame(other, other.withPendingBroughtForward(boundary, today))
    }

    @Test
    fun aViewersSwitchOffAndMarkedSwitchOnKeepTheirTimes() {
        val edited =
            lineup
                .with("delta") {
                    it.copy(state = ChannelState.UserHidden, viewerChoice = ViewerChoice.Touched, leavesAtMs = boundary)
                }.with("echo") { it.copy(onSinceMs = boundary, viewerChoice = ViewerChoice.Added) }

        val moved = edited.withPendingBroughtForward(boundary, today)

        assertEquals(edited.channel("delta"), moved.channel("delta"), "a hidden channel keeps its leaving time")
        assertEquals(edited.channel("echo"), moved.channel("echo"), "a viewer's switch-on keeps its mark and time")
    }

    @Test
    fun nothingChangesWhenNothingIsPendingForTheBoundary() {
        val quiet =
            lineup
                .with("alpha") { it.copy(onSinceMs = elsewhere) }
                .with("bravo") { it.copy(leavesAtMs = elsewhere, shelfReason = ShelfReason.BelowCap) }

        assertSame(quiet, quiet.withPendingBroughtForward(boundary, today))
        assertSame(lineup, lineup.withPendingBroughtForward(boundary, today))
    }
}
