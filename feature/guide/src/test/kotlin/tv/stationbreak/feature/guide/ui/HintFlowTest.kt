// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hint row's drop order: Menu goes first, then the Left hint, then OK; Back is never dropped. */
class HintFlowTest {
    // Display order: OK, Left, Back, Menu; each pair 100 px wide with 20 px between.
    private val widths = listOf(100, 100, 100, 100)
    private val ranks = listOf(HINT_RANK_OK, HINT_RANK_LEFT, HINT_RANK_BACK, HINT_RANK_MENU)

    @Test
    fun `everything shows when it fits`() {
        assertEquals(listOf(true, true, true, true), visibleHints(widths, ranks, 20, 460))
    }

    @Test
    fun `Menu is dropped first`() {
        assertEquals(listOf(true, true, true, false), visibleHints(widths, ranks, 20, 459))
    }

    @Test
    fun `then the Left hint, then OK`() {
        assertEquals(listOf(true, false, true, false), visibleHints(widths, ranks, 20, 339))
        assertEquals(listOf(false, false, true, false), visibleHints(widths, ranks, 20, 219))
    }

    @Test
    fun `a narrow later pair does not slip in past a dropped one`() {
        // Left (rank 2) does not fit; Menu (rank 3) is narrow enough to, but stays dropped.
        val shown = visibleHints(listOf(100, 200, 100, 20), ranks, 20, 260)
        assertEquals(listOf(true, false, true, false), shown)
    }

    @Test
    fun `Back is kept even when it alone is too wide`() {
        assertEquals(listOf(false, false, true, false), visibleHints(widths, ranks, 20, 50))
    }

    @Test
    fun `a row with no OK or Left keeps Back and Menu`() {
        assertEquals(listOf(true, true), visibleHints(listOf(100, 100), listOf(HINT_RANK_BACK, HINT_RANK_MENU), 20, 220))
        assertEquals(listOf(true, false), visibleHints(listOf(100, 100), listOf(HINT_RANK_BACK, HINT_RANK_MENU), 20, 219))
    }

    @Test
    fun `ranks follow the pairs that show, in display order`() {
        assertEquals(ranks, hintRanks(hasOk = true, hasLeft = true, hasMenu = true))
        assertEquals(listOf(HINT_RANK_BACK), hintRanks(hasOk = false, hasLeft = false, hasMenu = false))
        assertEquals(
            listOf(HINT_RANK_OK, HINT_RANK_BACK),
            hintRanks(hasOk = true, hasLeft = false, hasMenu = false),
        )
    }
}
