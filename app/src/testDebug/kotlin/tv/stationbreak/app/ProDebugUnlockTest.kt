// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** The release guard's counterpart (#793): debug builds keep Pro unlocked, so dogfood and device runs see no gate. */
class ProDebugUnlockTest {
    @Test
    fun aDebugBuildHasProUnlocked() {
        assertTrue("PRO_UNLOCKED must be true in a debug build", BuildConfig.PRO_UNLOCKED)
    }
}
