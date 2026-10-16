// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The settings type has no defaults (they live in core:rules only, so the two cannot drift) and validates itself when
 * it
 * is built. `settings(...)` below spells every field out on purpose.
 */
class SchedulerSettingsTest {
    @Suppress("LongParameterList") // Mirrors the type: every field is given, none is defaulted.
    private fun settings(
        orderingMode: OrderingMode = OrderingMode.Shuffle,
        seed: Seed = Seed(1uL),
        blockLength: Int = 1,
        alignToGrid: Boolean = true,
        gridMinutes: Int = 30,
        epochHours: Int = 24,
        utcOffsetMinutes: Int = 0,
        startMinuteOfDay: Int = 360,
        graceHours: Int = 6,
    ) = SchedulerSettings(
        orderingMode = orderingMode,
        seed = seed,
        blockLength = blockLength,
        alignToGrid = alignToGrid,
        gridMinutes = gridMinutes,
        epochHours = epochHours,
        utcOffsetMinutes = utcOffsetMinutes,
        startMinuteOfDay = startMinuteOfDay,
        graceHours = graceHours,
    )

    private fun rejects(build: () -> SchedulerSettings) {
        assertFailsWith<IllegalArgumentException> { build() }
    }

    @Test
    fun aValidSettingExposesItsMillisecondForms() {
        val s =
            settings(gridMinutes = 15, epochHours = 6, utcOffsetMinutes = 345, startMinuteOfDay = 1110, graceHours = 2)
        assertEquals(15 * 60_000L, s.gridMs)
        assertEquals(6 * 3_600_000L, s.epochMs)
        assertEquals(345 * 60_000L, s.offsetMs)
        assertEquals(1110 * 60_000L, s.startMs)
        assertEquals(2 * 3_600_000L, s.graceMs)
    }

    @Test
    fun theGridIsFifteenThirtyOrSixty() {
        for (g in listOf(15, 30, 60)) settings(gridMinutes = g)
        for (g in listOf(0, 1, 5, 10, 20, 45, 90, -30)) rejects { settings(gridMinutes = g) }
    }

    @Test
    fun theStartIsOnTheGrid() {
        settings(gridMinutes = 60, startMinuteOfDay = 360)
        settings(gridMinutes = 30, startMinuteOfDay = 390)
        settings(gridMinutes = 15, startMinuteOfDay = 405)
        rejects { settings(gridMinutes = 60, startMinuteOfDay = 390) }
        rejects { settings(gridMinutes = 30, startMinuteOfDay = 375) }
        rejects { settings(gridMinutes = 15, startMinuteOfDay = 370) }
    }

    @Test
    fun theStartIsInsideTheDay() {
        settings(startMinuteOfDay = 0)
        settings(startMinuteOfDay = 1410)
        rejects { settings(startMinuteOfDay = -30) }
        rejects { settings(startMinuteOfDay = 1440) }
    }

    @Test
    fun epochHoursMustDivideTwentyFour() {
        for (h in listOf(1, 2, 3, 4, 6, 8, 12, 24)) settings(epochHours = h)
        for (h in listOf(0, 5, 7, 9, 10, 18, 25, 48, -1)) rejects { settings(epochHours = h) }
    }

    @Test
    fun theOffsetIsInRangeAndAMultipleOfFifteen() {
        for (o in listOf(-720, -300, 0, 330, 345, 840)) settings(utcOffsetMinutes = o)
        for (o in listOf(-735, -721, 841, 855, 7, 10, 346)) rejects { settings(utcOffsetMinutes = o) }
    }

    @Test
    fun graceIsZeroToFortyEightHours() {
        for (g in listOf(0, 6, 48)) settings(graceHours = g)
        for (g in listOf(-1, 49, 1000)) rejects { settings(graceHours = g) }
    }

    @Test
    fun aBlockIsOneToOneHundredItems() {
        for (b in listOf(1, 2, 100)) settings(blockLength = b)
        for (b in listOf(0, -1, 101)) rejects { settings(blockLength = b) }
    }

    @Test
    fun theTypeHasNoDefaults() {
        // Every constructor parameter is required: the only way to build one is to name all nine fields.
        val s = settings()
        assertEquals(
            SchedulerSettings(
                OrderingMode.Shuffle,
                Seed(1uL),
                1,
                true,
                30,
                24,
                0,
                360,
                6,
            ),
            s,
        )
    }
}
