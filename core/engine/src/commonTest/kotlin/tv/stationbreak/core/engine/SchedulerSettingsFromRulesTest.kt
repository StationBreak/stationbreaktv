// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun aVersion2FeatureIsRefusedBySchedulerSettings() {
        val v2 = base.copy(formatVersion = 2)
        val features =
            listOf(
                v2.copy(ordering = Ordering(OrderingMode.Rotation)),
                v2.copy(ordering = Ordering(OrderingMode.InOrder)),
                v2.copy(sources = listOf(Source.Item(idOf(1)))),
                v2.copy(exclusions = listOf(Source.Item(idOf(1)))),
                v2.copy(filters = Filters(null, null, null, null, null, null)),
                v2.copy(ordering = Ordering(OrderingMode.Shuffle, OrderingCaps(null, null))),
            )
        for ((i, rules) in features.withIndex()) {
            val e = assertFailsWith<IllegalArgumentException>("case $i") { rules.toSchedulerSettings() }
            assertEquals("Rules format version 2 features are not scheduled yet", e.message)
        }
    }

    @Test
    fun aVersion2FileWithNoV2FeatureStillGivesSettings() {
        assertEquals(OrderingMode.Shuffle, base.copy(formatVersion = 2).toSchedulerSettings().orderingMode)
    }
}
