// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningLaunchTest {
    @Test
    fun aNewActivityShowsTheOpening() {
        assertTrue(showsOpening(restored = false, skippable = true, skipExtra = false))
        assertTrue(showsOpening(restored = false, skippable = false, skipExtra = false))
    }

    @Test
    fun aRestoreDoesNot() {
        assertFalse(showsOpening(restored = true, skippable = true, skipExtra = false))
        assertFalse(showsOpening(restored = true, skippable = false, skipExtra = false))
    }

    @Test
    fun theSkipExtraWorksOnlyOnASkippableBuild() {
        assertFalse(showsOpening(restored = false, skippable = true, skipExtra = true))
        assertTrue(showsOpening(restored = false, skippable = false, skipExtra = true))
    }

    @Test
    fun theExtraIsNamedForTheHarness() {
        assertEquals("stationbreak.skipOpening", EXTRA_SKIP_OPENING)
    }
}
