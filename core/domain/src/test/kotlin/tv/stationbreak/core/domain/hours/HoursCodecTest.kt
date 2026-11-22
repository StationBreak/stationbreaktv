// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlin.test.Test
import kotlin.test.assertEquals

class HoursCodecTest {
    private val schedule =
        HoursSchedule(
            listOf(
                HoursPeriod(SUNDAY, 20 * 60, 6 * 60, AudienceLevel.OffAir),
                HoursPeriod(MONDAY, 0, 1440, AudienceLevel.Family),
            ),
        )

    @Test
    fun roundTrips() {
        assertEquals(schedule, HoursCodec.decode(HoursCodec.encode(schedule)))
        assertEquals(HoursSchedule.EMPTY, HoursCodec.decode(HoursCodec.encode(HoursSchedule.EMPTY)))
    }

    @Test
    fun theFormatIsPinned() {
        assertEquals(
            """{"v":1,"periods":[{"d":1,"s":0,"e":1440,"l":"Family"}]}""",
            HoursCodec.encode(HoursSchedule(listOf(schedule.periods[1]))),
        )
    }

    @Test
    fun absentCorruptFutureAndOutOfRangeInputAllReadAsNoSchedule() {
        listOf(
            null,
            "",
            "not json",
            "{",
            "[]",
            """{"v":2,"periods":[{"d":1,"s":0,"e":60,"l":"Kids"}]}""",
            """{"v":0,"periods":[]}""",
            """{"periods":[]}""",
            """{"v":1,"periods":[{"d":8,"s":0,"e":60,"l":"Kids"}]}""",
            """{"v":1,"periods":[{"d":1,"s":60,"e":60,"l":"Kids"}]}""",
            """{"v":1,"periods":[{"d":1,"s":0,"e":99999,"l":"Kids"}]}""",
            """{"v":1,"periods":[{"d":1,"s":0,"e":60,"l":"Toddler"}]}""",
            """{"v":1,"periods":[{"d":"x","s":0,"e":60,"l":"Kids"}]}""",
            """{"v":1,"periods":[{"d":1,"s":0,"e":60,"l":"Kids"},{"d":1}]}""",
        ).forEach { assertEquals(HoursSchedule.EMPTY, HoursCodec.decode(it), "input: $it") }
    }
}
