// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchedulerSettingsFromRulesTest {
    private val base = rules(listOf(Source.Genre("Comedy")))

    @Test
    fun defaultsAreTheRulesFormatDefaults() {
        val timing = Timing(null, null, null, null, null, null, BroadcastDay(-300, null))
        val s = base.copy(seed = Seed(7uL), timing = timing).toSchedulerSettings()
        assertEquals(OrderingMode.Shuffle, s.orderingMode)
        assertEquals(Seed(7uL), s.seed)
        assertEquals(1, s.blockLength)
        assertEquals(true, s.alignToGrid)
        assertEquals(30, s.gridMinutes)
        assertEquals(24, s.epochHours)
        assertEquals(-300, s.utcOffsetMinutes)
        assertEquals(360, s.startMinuteOfDay)
        assertEquals(6, s.graceHours)
        assertEquals(60_000L, s.minRuntimeMs)
    }

    @Test
    fun everyFieldIsCarried() {
        val timing = Timing(3, false, 12, 2, 60, 90, BroadcastDay(345, "18:00"))
        val s = base.copy(ordering = Ordering(OrderingMode.RandomInOrder), timing = timing).toSchedulerSettings()
        assertEquals(OrderingMode.RandomInOrder, s.orderingMode)
        assertEquals(3, s.blockLength)
        assertEquals(false, s.alignToGrid)
        assertEquals(60, s.gridMinutes)
        assertEquals(12, s.epochHours)
        assertEquals(345, s.utcOffsetMinutes)
        assertEquals(1080, s.startMinuteOfDay)
        assertEquals(2, s.graceHours)
        assertEquals(90_000L, s.minRuntimeMs)
    }

    @Test
    fun startWithMinutesIsParsed() {
        val timing = Timing(null, null, null, null, null, null, BroadcastDay(0, "05:30"))
        assertEquals(330, base.copy(timing = timing).toSchedulerSettings().startMinuteOfDay)
    }

    private val v2 = base.copy(formatVersion = 2)

    @Test
    fun rotationGivesCapsWithDefaults3And1() {
        val s = v2.copy(ordering = Ordering(OrderingMode.Rotation)).toSchedulerSettings()
        assertEquals(OrderingMode.Rotation, s.orderingMode)
        assertEquals(Caps(3, 1), s.caps)
        val given = v2.copy(ordering = Ordering(OrderingMode.Rotation, OrderingCaps(5, null))).toSchedulerSettings()
        assertEquals(Caps(5, 1), given.caps)
    }

    @Test
    fun inOrderGivesNoCaps() {
        val s = v2.copy(ordering = Ordering(OrderingMode.InOrder, OrderingCaps(4, 2))).toSchedulerSettings()
        assertEquals(OrderingMode.InOrder, s.orderingMode)
        assertNull(s.caps)
    }

    @Test
    fun shuffleWithoutCapsGivesNoneAndWithCapsGivesThem() {
        assertNull(v2.copy(ordering = Ordering(OrderingMode.Shuffle)).toSchedulerSettings().caps)
        val capped = v2.copy(ordering = Ordering(OrderingMode.Shuffle, OrderingCaps(2, 2))).toSchedulerSettings()
        assertEquals(Caps(2, 2), capped.caps)
    }

    @Test
    fun aRuntimeFilterBecomesMilliseconds() {
        val both = v2.copy(filters = Filters(null, null, null, 30, 120, null)).toSchedulerSettings()
        assertEquals(RuntimeFilter(1_800_000L, 7_200_000L), both.runtimeFilter)
        val minOnly = v2.copy(filters = Filters(null, null, null, 45, null, null)).toSchedulerSettings()
        assertEquals(RuntimeFilter(2_700_000L, null), minOnly.runtimeFilter)
        val genresOnly = v2.copy(filters = Filters(listOf("Comedy"), null, null, null, null, null))
        assertNull(genresOnly.toSchedulerSettings().runtimeFilter)
    }

    @Test
    fun v1RulesGiveV1Settings() {
        val s = base.toSchedulerSettings()
        assertNull(s.caps)
        assertNull(s.runtimeFilter)
    }

    @Test
    fun aVersion2FileWithNoV2FeatureStillGivesSettings() {
        assertEquals(OrderingMode.Shuffle, base.copy(formatVersion = 2).toSchedulerSettings().orderingMode)
    }
}
