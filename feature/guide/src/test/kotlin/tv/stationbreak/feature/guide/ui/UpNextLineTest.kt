// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A focused break's meta line in the panel: what is up next, and when. */
class UpNextLineTest {
    private val copy =
        UpNextCopy(
            titleAt = { title, time -> "Up next: $title at $time" },
            at = { "Up next at $it" },
        )

    @Test
    fun theNextProgramsTitleAndTime() {
        assertEquals("Up next: Roman Holiday at 8:30 PM", upNextLine("Roman Holiday", "8:30 PM", copy))
    }

    @Test
    fun theTimeAloneWhenTheNextProgramHasNoTitle() {
        assertEquals("Up next at 8:30 PM", upNextLine(null, "8:30 PM", copy))
        assertEquals("Up next at 8:30 PM", upNextLine("  ", "8:30 PM", copy))
    }
}
