// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        minRuntimeMs: Long = 60_000L,
        caps: Caps? = null,
        runtimeFilter: RuntimeFilter? = null,
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
        minRuntimeMs = minRuntimeMs,
        caps = caps,
        runtimeFilter = runtimeFilter,
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
    fun theMinimumRuntimeIsOneSecondToOneHour() {
        for (ms in listOf(1_000L, 60_000L, 3_600_000L)) assertEquals(ms, settings(minRuntimeMs = ms).minRuntimeMs)
        for (ms in listOf(Long.MIN_VALUE, -1L, 0L, 999L, 3_600_001L, Long.MAX_VALUE)) {
            rejects { settings(minRuntimeMs = ms) }
        }
    }

    @Test
    fun aBlockIsOneToOneHundredItems() {
        for (b in listOf(1, 2, 100)) settings(blockLength = b)
        for (b in listOf(0, -1, 101)) rejects { settings(blockLength = b) }
    }

    @Test
    fun theTypeHasNoDefaults() {
        // Every constructor parameter is required: the only way to build one is to name all twelve fields.
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
                60_000L,
                null,
                null,
            ),
            s,
        )
    }

    @Test
    fun theFormatLimitsAreTheLargestValuesTheTypeAccepts() {
        // The history retention is checked against these (spec 09, Vimes L4), so they must be the real limits.
        assertEquals(24 * 3_600_000L, SchedulerSettings.MAX_EPOCH_MS)
        assertEquals(48 * 3_600_000L, SchedulerSettings.MAX_GRACE_MS)
        val largest = settings(epochHours = 24, graceHours = 48)
        assertEquals(SchedulerSettings.MAX_EPOCH_MS, largest.epochMs)
        assertEquals(SchedulerSettings.MAX_GRACE_MS, largest.graceMs)
        rejects { settings(graceHours = 49) }
    }

    @Test
    fun capsRangeIs1To10() {
        for (n in listOf(1, 3, 10)) {
            Caps(series = n, film = n)
        }
        for (n in listOf(0, -1, 11)) {
            rejects { settings(caps = Caps(series = n, film = 1)) }
            rejects { settings(caps = Caps(series = 1, film = n)) }
        }
    }

    @Test
    fun aRuntimeFilterNeedsABound() {
        rejects { settings(runtimeFilter = RuntimeFilter(minMs = null, maxMs = null)) }
        RuntimeFilter(minMs = 1L, maxMs = null)
        RuntimeFilter(minMs = null, maxMs = 1L)
    }

    @Test
    fun aRuntimeFilterRejectsMinAboveMax() {
        RuntimeFilter(minMs = 5L, maxMs = 5L)
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(minMs = 6L, maxMs = 5L) }
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(minMs = 0L, maxMs = null) }
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(minMs = null, maxMs = 0L) }
    }

    @Test
    fun isAirableAppliesTheRuntimeFilter() {
        val s = settings(runtimeFilter = RuntimeFilter(minMs = 30 * 60_000L, maxMs = 60 * 60_000L))
        assertFalse(s.isAirable(30 * 60_000L - 1, ScheduleItemKind.Movie))
        assertTrue(s.isAirable(30 * 60_000L, ScheduleItemKind.Movie))
        assertTrue(s.isAirable(60 * 60_000L, ScheduleItemKind.Movie))
        assertFalse(s.isAirable(60 * 60_000L + 1, ScheduleItemKind.Movie))
        val low = settings(minRuntimeMs = 120_000L, runtimeFilter = RuntimeFilter(minMs = null, maxMs = 60 * 60_000L))
        assertFalse(low.isAirable(119_999L, ScheduleItemKind.Movie))
        assertTrue(low.isAirable(120_000L, ScheduleItemKind.Movie))
    }

    @Test
    fun noFilterLeavesIsAirableAsBefore() {
        val s = settings()
        assertFalse(s.isAirable(59_999L, ScheduleItemKind.Movie))
        assertTrue(s.isAirable(60_000L, ScheduleItemKind.Movie))
        assertTrue(s.isAirable(s.epochMs, ScheduleItemKind.Movie))
        assertFalse(s.isAirable(s.epochMs + 1, ScheduleItemKind.Movie))
    }

    @Test
    fun rotationNeedsCaps() {
        rejects { settings(orderingMode = OrderingMode.Rotation, caps = null) }
        settings(orderingMode = OrderingMode.Rotation, caps = Caps(series = 3, film = 1))
    }

    @Test
    fun capsNeedA24HourEpoch() {
        val caps = Caps(series = 3, film = 1)
        rejects { settings(epochHours = 12, caps = caps) }
        settings(epochHours = 24, caps = caps)
        settings(orderingMode = OrderingMode.Chronological, epochHours = 12, caps = null)
    }

    @Test
    fun anEpisodeWindowDoesNotTouchFilms() {
        val min = 60_000L
        val s = settings(runtimeFilter = RuntimeFilter(minMs = null, maxMs = null, episodeMaxMs = 30 * min))
        assertTrue(s.isAirable(30 * min, ScheduleItemKind.Episode))
        assertFalse(s.isAirable(30 * min + 1, ScheduleItemKind.Episode))
        assertTrue(s.isAirable(120 * min, ScheduleItemKind.Movie))
        val films = settings(runtimeFilter = RuntimeFilter(null, null, movieMinMs = 80 * min, movieMaxMs = 150 * min))
        assertTrue(films.isAirable(10 * min, ScheduleItemKind.Episode))
        assertFalse(films.isAirable(79 * min, ScheduleItemKind.Movie))
        assertTrue(films.isAirable(80 * min, ScheduleItemKind.Movie))
        assertFalse(films.isAirable(151 * min, ScheduleItemKind.Movie))
    }

    @Test
    fun theGeneralWindowAndTheKindWindowBothApply() {
        val min = 60_000L
        val filter = RuntimeFilter(minMs = null, maxMs = 180 * min, episodeMaxMs = 30 * min, movieMinMs = 80 * min)
        val s = settings(runtimeFilter = filter)
        assertTrue(s.isAirable(30 * min, ScheduleItemKind.Episode))
        assertFalse(s.isAirable(31 * min, ScheduleItemKind.Episode))
        assertFalse(s.isAirable(79 * min, ScheduleItemKind.Movie))
        assertTrue(s.isAirable(180 * min, ScheduleItemKind.Movie))
        assertFalse(s.isAirable(181 * min, ScheduleItemKind.Movie))
    }

    @Test
    fun aKindWindowIsValidatedLikeTheGeneralOne() {
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(null, null) }
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(null, null, episodeMinMs = 6L, episodeMaxMs = 5L) }
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(null, null, movieMinMs = 0L) }
        assertFailsWith<IllegalArgumentException> { RuntimeFilter(null, null, movieMaxMs = 0L) }
        RuntimeFilter(null, null, movieMaxMs = 1L)
    }
}
