// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The per-epoch pool (spec 08, Unit 1). With `S = epochStart(n)` and `G = graceHours * 3,600,000`, an item is in epoch
 * `n`'s pool when `createdAt < S - G`, `deletedAt` is absent or `>= S - G`, and `0 < runtime <= epochMs`.
 *
 * The channel below is UTC-5, day starts 06:00, 24-hour epochs, grace 6 h. For `n = 0`: `S = 39,600,000`
 * (1970-01-01T11:00Z)
 * and `S - G = 18,000,000`.
 */
class EpochPoolTest {
    private val settings =
        testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360, epochHours = 24, graceHours = 6)
    private val cutoff = 18_000_000L

    private fun poolIds(
        vararg items: ScheduleItem,
        n: Long = 0L,
        s: SchedulerSettings = settings,
    ) = EpochPool.build(s, items.toList(), n).map { it.id.value.first() }

    @Test
    fun theCutoffIsEpochStartMinusGrace() {
        assertEquals(39_600_000L, Epochs.epochStart(settings, 0L))
        assertEquals(cutoff, Epochs.epochStart(settings, 0L) - settings.graceMs)
    }

    @Test
    fun createdJustBeforeTheCutoffIsInAndAtOrAfterItIsOut() {
        val ids =
            poolIds(
                testItem(idOf('1'), createdAtMs = cutoff - 1),
                testItem(idOf('2'), createdAtMs = cutoff),
                testItem(idOf('3'), createdAtMs = cutoff + 1),
            )
        assertEquals(listOf('1'), ids)
    }

    @Test
    fun anItemCreatedBeforeThe1970EpochIsIn() {
        assertEquals(listOf('1'), poolIds(testItem(idOf('1'), createdAtMs = -9_000_000_000L)))
    }

    @Test
    fun notDeletedIsIn() {
        assertEquals(listOf('1'), poolIds(testItem(idOf('1'), deletedAtMs = null)))
    }

    @Test
    fun deletedBeforeTheCutoffIsOutAndAtOrAfterItIsIn() {
        val ids =
            poolIds(
                testItem(idOf('1'), deletedAtMs = cutoff - 1),
                testItem(idOf('2'), deletedAtMs = cutoff),
                testItem(idOf('3'), deletedAtMs = cutoff + 1),
                testItem(idOf('4'), deletedAtMs = Long.MAX_VALUE),
            )
        assertEquals(listOf('2', '3', '4'), ids)
    }

    @Test
    fun anItemExactlyAtTheCutoffIsInIfDeletedThereAndOutIfCreatedThere() {
        val created = testItem(idOf('1'), createdAtMs = cutoff)
        val deleted = testItem(idOf('2'), createdAtMs = 0L, deletedAtMs = cutoff)
        assertEquals(listOf('2'), poolIds(created, deleted))
    }

    @Test
    fun aRuntimeBelowTheMinimumIsOutAndTheMinimumItselfIsIn() {
        // The default minimum is 60 s (testSettings): 59,999 ms is out, 60,000 and 60,001 are in.
        val ids =
            poolIds(
                testItem(idOf('1'), runtimeMs = 0L),
                testItem(idOf('2'), runtimeMs = -1L),
                testItem(idOf('3'), runtimeMs = 59_999L),
                testItem(idOf('4'), runtimeMs = 60_000L),
                testItem(idOf('5'), runtimeMs = 60_001L),
                testItem(idOf('6'), runtimeMs = Long.MIN_VALUE),
            )
        assertEquals(listOf('4', '5'), ids)
    }

    @Test
    fun theMinimumIsTheSettingsOwn() {
        val oneSecond = testSettings(minRuntimeMs = 1_000L)
        val items = listOf(testItem(idOf('1'), runtimeMs = 999L), testItem(idOf('2'), runtimeMs = 1_000L))
        assertEquals(listOf('2'), EpochPool.build(oneSecond, items, 0L).map { it.id.value.first() })
        val oneHour = testSettings(minRuntimeMs = 3_600_000L)
        val longer = listOf(testItem(idOf('1'), runtimeMs = 3_599_999L), testItem(idOf('2'), runtimeMs = 3_600_000L))
        assertEquals(listOf('2'), EpochPool.build(oneHour, longer, 0L).map { it.id.value.first() })
    }

    @Test
    fun aRuntimeEqualToTheEpochIsInAndLongerIsOut() {
        val epochMs = settings.epochMs
        val ids =
            poolIds(
                testItem(idOf('1'), runtimeMs = epochMs),
                testItem(idOf('2'), runtimeMs = epochMs + 1),
                testItem(idOf('3'), runtimeMs = epochMs - 1),
                testItem(idOf('4'), runtimeMs = Long.MAX_VALUE),
            )
        assertEquals(listOf('1', '3'), ids)
    }

    @Test
    fun theEpochLengthDecidesHowLongIsTooLong() {
        val sixHours = testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360, epochHours = 6, graceHours = 6)
        val ids =
            poolIds(
                testItem(idOf('1'), runtimeMs = 6 * MS_HOUR),
                testItem(idOf('2'), runtimeMs = 6 * MS_HOUR + 1),
                s = sixHours,
            )
        assertEquals(listOf('1'), ids)
    }

    @Test
    fun graceIsPartOfTheCutoffAndZeroGraceMeansTheEpochStart() {
        val none = testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360, graceHours = 0)
        val s = Epochs.epochStart(none, 0L)
        assertEquals(
            listOf('1'),
            poolIds(
                testItem(idOf('1'), createdAtMs = s - 1),
                testItem(idOf('2'), createdAtMs = s),
                s = none,
            ),
        )
        val wide = testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360, graceHours = 48)
        val wideCutoff = Epochs.epochStart(wide, 0L) - 48 * MS_HOUR
        assertEquals(
            listOf('1'),
            poolIds(
                testItem(idOf('1'), createdAtMs = wideCutoff - 1),
                testItem(idOf('2'), createdAtMs = wideCutoff),
                s = wide,
            ),
        )
    }

    @Test
    fun theCutoffMovesWithTheEpoch() {
        val item = testItem(idOf('1'), createdAtMs = cutoff)
        assertEquals(emptyList(), poolIds(item, n = 0L))
        assertEquals(listOf('1'), poolIds(item, n = 1L)) // one day later, the cutoff is 24 h later
        assertEquals(emptyList(), poolIds(item, n = -1L))
    }

    @Test
    fun theWholeRuleCombinesAndTheResultIsInCanonicalIdOrder() {
        val items =
            listOf(
                testItem(idOf('f')),
                testItem(idOf('a'), runtimeMs = 0L),
                testItem(idOf('c')),
                testItem(idOf('b'), createdAtMs = cutoff),
                testItem(idOf('9'), deletedAtMs = cutoff - 1),
                testItem(idOf('1'), deletedAtMs = cutoff),
            )
        val pool = EpochPool.build(settings, items, 0L)
        assertEquals(listOf('1', 'c', 'f'), pool.map { it.id.value.first() })
        assertTrue(pool.zipWithNext().all { (x, y) -> x.id.value < y.id.value })
    }

    @Test
    fun anEmptyInputGivesAnEmptyPool() {
        assertEquals(emptyList(), EpochPool.build(settings, emptyList(), 0L))
    }

    @Test
    fun duplicateIdsAreRejectedWithAClearError() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                EpochPool.build(settings, listOf(testItem(idOf('1')), testItem(idOf('2')), testItem(idOf('1'))), 0L)
            }
        assertTrue(idOf('1').value in e.message.orEmpty(), e.message)
        assertTrue("uplicate" in e.message.orEmpty(), e.message)
    }

    @Test
    fun duplicatesAreRejectedEvenWhenTheEpochWouldLeaveThemOut() {
        assertFailsWith<IllegalArgumentException> {
            val zero = testItem(idOf('1'), runtimeMs = 0L)
            EpochPool.build(settings, listOf(zero, zero), 0L)
        }
    }
}
