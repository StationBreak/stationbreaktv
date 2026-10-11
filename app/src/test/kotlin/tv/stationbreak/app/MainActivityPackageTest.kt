// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityPackageTest {
    @Test
    fun activityLivesInTheApplicationIdPackage() {
        // ADR-0010: the Kotlin package root matches the application ID.
        assertEquals("tv.stationbreak.app", MainActivity::class.java.packageName)
    }

    @Test
    fun deliberatelyFails() {
        assertEquals(1, 2)
    }
}
