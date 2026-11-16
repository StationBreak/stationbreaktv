// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The release guard (#793): the free tier's gates must be closed in every release build, so v1 cannot ship with Pro
 * unlocked by mistake. This source set is compiled only for release variants. #119 decides the GitHub build: if it
 * ever unlocks, that is a deliberate edit of the table in app/build.gradle.kts and of this test.
 */
class ProReleaseGuardTest {
    @Test
    fun aReleaseBuildShipsWithProLocked() {
        assertFalse("PRO_UNLOCKED must be false in a release build", BuildConfig.PRO_UNLOCKED)
    }
}
