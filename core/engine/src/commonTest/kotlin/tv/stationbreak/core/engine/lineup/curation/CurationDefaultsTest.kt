// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import kotlin.test.Test
import kotlin.test.assertEquals

class CurationDefaultsTest {
    @Test
    fun theShareCapIsAQuarterWithAnHourFloor() {
        assertEquals(3_600_000L, CurationDefaults.shareCapMs(0))
        assertEquals(3_600_000L, CurationDefaults.shareCapMs(14_400_000L))
        assertEquals(25_000_000L, CurationDefaults.shareCapMs(100_000_000L))
    }
}
