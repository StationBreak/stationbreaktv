// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.DaySnapshotEvent
import tv.stationbreak.core.domain.lineup.DaySnapshotMiss

/** The days kept on disk (#480): numbers-only log lines, and an app identity that moves with every install. */
class DaySnapshotLogTest {
    @Test
    fun eachEventIsOneNumbersOnlyLine() {
        assertEquals("DaySnapshot hit kb=1714 ms=16", daySnapshotLine(DaySnapshotEvent.Hit(1_755_136, 16)))
        val miss = DaySnapshotEvent.Miss(DaySnapshotMiss.Generation)
        assertEquals("DaySnapshot miss reason=Generation", daySnapshotLine(miss))
        assertEquals("DaySnapshot saved kb=2 ms=40", daySnapshotLine(DaySnapshotEvent.Saved(2_048, 40)))
        assertEquals("DaySnapshot saveFailed", daySnapshotLine(DaySnapshotEvent.SaveFailed))
    }

    @Test
    fun theAppBuildMovesWithTheVersionCodeAndWithEveryInstall() {
        val installed = appBuildOf(1, 1_767_225_600_000L)
        assertEquals("1:1767225600000", installed)
        assertNotEquals(installed, appBuildOf(2, 1_767_225_600_000L))
        assertNotEquals(installed, appBuildOf(1, 1_767_225_600_001L))
    }

    @Test
    fun noInstallTimeMeansNoDaysOnDisk() {
        assertNull(appBuildOf(1, null))
        assertNull(appBuildOf(1, 0L))
    }
}
