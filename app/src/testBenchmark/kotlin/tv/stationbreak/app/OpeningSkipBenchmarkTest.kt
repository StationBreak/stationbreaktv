// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** The benchmark build, which measures what ships, can still skip Stan's opening so its runs start on the app. */
class OpeningSkipBenchmarkTest {
    @Test
    fun aBenchmarkBuildHonoursTheSkipExtra() {
        assertTrue("OPENING_SKIPPABLE must be true in a benchmark build", BuildConfig.OPENING_SKIPPABLE)
    }
}
