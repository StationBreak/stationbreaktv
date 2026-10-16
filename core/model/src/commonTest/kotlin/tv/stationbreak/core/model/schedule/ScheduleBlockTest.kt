// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleBlockTest {
    private val id = CanonicalId.parse("11111111111111111111111111111111")

    @Test
    fun aBlockIsHalfOpenAndATimeOnAnEdgeBelongsToTheLaterBlock() {
        val first = ScheduleBlock(1_000L, 2_000L, ScheduleContent.Program(id))
        val second = ScheduleBlock(2_000L, 3_000L, ScheduleContent.Slate)
        assertTrue(first.contains(1_000L))
        assertTrue(first.contains(1_999L))
        assertFalse(first.contains(2_000L))
        assertTrue(second.contains(2_000L))
        assertFalse(first.contains(999L))
        assertEquals(1_000L, first.lengthMs)
    }

    @Test
    fun aZeroLengthOrBackwardsBlockIsNeverBuilt() {
        assertFailsWith<IllegalArgumentException> { ScheduleBlock(5L, 5L, ScheduleContent.Slate) }
        assertFailsWith<IllegalArgumentException> { ScheduleBlock(6L, 5L, ScheduleContent.Slate) }
    }

    @Test
    fun aProgramStartsAtOffsetZeroInVersionOne() {
        val program = ScheduleContent.Program(id)
        assertEquals(0L, program.mediaStartOffsetMs)
        assertFailsWith<IllegalArgumentException> { ScheduleContent.Program(id, mediaStartOffsetMs = -1L) }
    }

    @Test
    fun aBlockHoldsAnItemOrASlate() {
        val program = ScheduleBlock(0L, 10L, ScheduleContent.Program(id))
        val slate = ScheduleBlock(10L, 20L, ScheduleContent.Slate)
        assertEquals(id, (program.content as ScheduleContent.Program).itemId)
        assertEquals(ScheduleContent.Slate, slate.content)
    }
}
