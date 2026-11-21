// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The store release guard (#793): the free tier's gates must be closed in the Amazon release build, so v1 cannot ship
 * with Pro unlocked by mistake. This source set is compiled only for the Amazon release variant; the GitHub release
 * is unlocked on purpose (Q-BIZ-1, #119) and has its own test.
 */
class ProReleaseGuardTest {
    @Test
    fun theStoreReleaseShipsWithProLocked() {
        assertFalse("PRO_UNLOCKED must be false in the store release build", BuildConfig.PRO_UNLOCKED)
    }
}
