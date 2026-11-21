// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** The GitHub release ships with Pro fully unlocked (Q-BIZ-1, #119): it is GPL, with no store billing. */
class ProGithubUnlockTest {
    @Test
    fun theGithubReleaseShipsWithProUnlocked() {
        assertTrue("PRO_UNLOCKED must be true in the GitHub release build", BuildConfig.PRO_UNLOCKED)
    }
}
