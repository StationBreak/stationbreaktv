// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate harness can skip Stan's opening in a debug build. */
class OpeningSkipDebugTest {
    @Test
    fun aDebugBuildHonoursTheSkipExtra() {
        assertTrue("OPENING_SKIPPABLE must be true in a debug build", BuildConfig.OPENING_SKIPPABLE)
    }
}
