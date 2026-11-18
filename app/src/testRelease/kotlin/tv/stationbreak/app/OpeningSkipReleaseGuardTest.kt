// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertFalse
import org.junit.Test

/** A release build never honours the intent extra that skips Stan's opening. */
class OpeningSkipReleaseGuardTest {
    @Test
    fun aReleaseBuildIgnoresTheSkipExtra() {
        assertFalse("OPENING_SKIPPABLE must be false in a release build", BuildConfig.OPENING_SKIPPABLE)
    }
}
