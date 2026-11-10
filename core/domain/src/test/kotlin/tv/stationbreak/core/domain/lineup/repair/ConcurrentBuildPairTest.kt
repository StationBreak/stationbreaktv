// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.ConcurrentBuildPair
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fixture the emulator's heap test of two concurrent builds (#684) uses: it makes both builds' days. */
internal class ConcurrentBuildPairTest {
    private val libs =
        listOf(
            SyntheticLib(ItemId("d1".repeat(16)), '1', 300, 11L, null, false, null, false),
            SyntheticLib(ItemId("d2".repeat(16)), '2', 200, 12L, null, false, null, false),
        )

    @Test
    fun bothBuildsMakeTheirChannelDays() =
        runBlocking {
            val both = libs.mapTo(LinkedHashSet()) { it.id }
            val pair = ConcurrentBuildPair(seededLibrary(libs), ACCOUNT)
            pair.prepare(both)

            val made = pair.buildBoth(after = setOf(libs[0].id), before = both, at = NOON)

            assertTrue(made > 0, "channel days were made")
            assertEquals(0, made % 2, "the same channels in both builds")
        }
}
