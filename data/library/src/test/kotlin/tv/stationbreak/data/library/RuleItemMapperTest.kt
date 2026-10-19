// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 10 (Granny L4): a season ID that is not a canonical ID reads as no season, and the mapper counts it, so a
 * diagnostic can say how many items had one. The item itself is kept.
 */
class RuleItemMapperTest {
    private val row =
        HeldRow(hexId(1), MOVIES.value, "Episode", hexId(2), 1, 1, 1_800_000L, 1_000L, null, null, null, 0L)

    private fun mapped(
        mapper: RuleItemMapper,
        held: HeldRow,
    ) = mapper.of(ScheduleInputs.read(listOf(held)).accepted.single())

    @Test
    fun aSeasonIdThatIsNotCanonicalReadsAsNoSeasonAndIsCounted() {
        val mapper = RuleItemMapper()

        val item = mapped(mapper, row.copy(seasonId = "season-one"))

        assertNull(item.seasonId)
        assertEquals(1, mapper.seasonIdsNotCanonical)
    }

    @Test
    fun aCanonicalSeasonIdOrNoneIsNotCounted() {
        val mapper = RuleItemMapper()

        assertEquals(canon(7), mapped(mapper, row.copy(seasonId = hexId(7))).seasonId)
        assertNull(mapped(mapper, row.copy(seasonId = null)).seasonId)
        assertEquals(0, mapper.seasonIdsNotCanonical)
    }
}
