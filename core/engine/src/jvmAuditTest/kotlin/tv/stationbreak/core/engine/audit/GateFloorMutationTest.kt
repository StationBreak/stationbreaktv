// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.Band
import kotlin.test.Test
import kotlin.test.assertTrue

/** #1097: the floor rule of the gate, each band's floor broken on purpose in the real everything-on view ([Leaks]). */
class GateFloorMutationTest {
    private val leaks = Leaks()
    private val view = leaks.view

    @Test
    fun aChannelBelowItsFloorFails() {
        assertTrue(Rule.BelowFloor !in leaks.broken(view), "the real view keeps every channel at or above its floor")
        for (band in listOf(Band.Family, Band.Teen, Band.General)) {
            val floor = band.numbers.first
            val key = view.channels.first { it.floor == floor }.key
            val moved = view.channels.map { if (it.key == key) it.copy(number = floor - 1) else it }
            assertTrue(Rule.BelowFloor in leaks.broken(leaks.withChannels(moved)), "$band, $key")
        }
    }
}
