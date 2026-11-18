// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningLaunchTest {
    @Test
    fun aNewActivityShowsTheOpening() {
        assertTrue(showsOpening(restored = false, debuggable = true, skipExtra = false))
        assertTrue(showsOpening(restored = false, debuggable = false, skipExtra = false))
    }

    @Test
    fun aRestoreDoesNot() {
        assertFalse(showsOpening(restored = true, debuggable = true, skipExtra = false))
        assertFalse(showsOpening(restored = true, debuggable = false, skipExtra = false))
    }

    @Test
    fun theSkipExtraWorksOnlyOnADebuggableBuild() {
        assertFalse(showsOpening(restored = false, debuggable = true, skipExtra = true))
        assertTrue(showsOpening(restored = false, debuggable = false, skipExtra = true))
    }

    @Test
    fun theExtraIsNamedForTheHarness() {
        assertEquals("stationbreak.skipOpening", EXTRA_SKIP_OPENING)
    }
}
