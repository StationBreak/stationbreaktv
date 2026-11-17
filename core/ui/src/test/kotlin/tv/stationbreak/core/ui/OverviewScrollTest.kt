// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The program info panel's overview scrolls a few lines to a press of Down or Up (#703). */
class OverviewScrollTest {
    private val density = Density(2f)
    private val step = 2 * 3 * 18 // three 18 dp lines at density 2

    @Test
    fun downScrollsThreeLinesAndUpScrollsBack() =
        runBlocking {
            val scroll = ScrollState(0)
            scroll.stepOverview(forward = true, density)
            assertEquals(step, scroll.value)
            scroll.stepOverview(forward = true, density)
            assertEquals(2 * step, scroll.value)
            scroll.stepOverview(forward = false, density)
            assertEquals(step, scroll.value)
        }

    @Test
    fun upAtTheTopStaysAtTheTop() =
        runBlocking {
            val scroll = ScrollState(0)
            scroll.stepOverview(forward = false, density)
            assertEquals(0, scroll.value)
        }
}
