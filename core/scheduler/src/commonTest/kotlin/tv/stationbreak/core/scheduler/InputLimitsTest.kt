// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The input limits of docs/rules-format.md ("Numbers and ranges"): times from -2^52 to 2^52 inclusive, fewer than 2^26
 * items, and no filtering of bad input (that is the caller's job). A bad input makes `buildDay` reject the whole input.
 */
class InputLimitsTest {
    private val limit = 1L shl 52
    private val settings = testSettings(epochHours = 24, startMinuteOfDay = 0)

    @Test
    fun aCreatedAtOrDeletedAtInsideTheBoundsIsAccepted() {
        val items = listOf(testItem(idOf('1'), createdAtMs = -limit, deletedAtMs = limit))
        buildDay(settings, items, 0L)
    }

    @Test
    fun aCreatedAtBeyondTheBoundsRejectsTheWholeInput() {
        assertFailsWith<IllegalArgumentException> {
            buildDay(settings, listOf(testItem(idOf('1'), createdAtMs = limit + 1)), 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            buildDay(settings, listOf(testItem(idOf('1'), createdAtMs = -limit - 1)), 0L)
        }
    }

    @Test
    fun aDeletedAtBeyondTheBoundsRejectsTheWholeInput() {
        assertFailsWith<IllegalArgumentException> {
            buildDay(settings, listOf(testItem(idOf('1'), deletedAtMs = limit + 1)), 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            buildDay(settings, listOf(testItem(idOf('1'), deletedAtMs = -limit - 1)), 0L)
        }
    }

    @Test
    fun aBadItemRejectsTheInputEvenWhenThePoolWouldLeaveItOut() {
        // Too short for the pool, and out of range: still an input error.
        assertFailsWith<IllegalArgumentException> {
            buildDay(settings, listOf(testItem(idOf('1'), runtimeMs = 1L, createdAtMs = limit + 1)), 0L)
        }
    }

    @Test
    fun aDayWhoseStartOrEndIsOutOfRangeIsRejected() {
        // With start 00:00 and offset 0, epochStart(n) = n days. The last n with epochStart(n + 1) <= 2^52:
        val lastGoodN = limit / MS_DAY - 1
        buildDay(settings, emptyList(), lastGoodN)
        buildDay(settings, emptyList(), -(lastGoodN + 1))
        assertFailsWith<IllegalArgumentException> { buildDay(settings, emptyList(), lastGoodN + 1) }
        assertFailsWith<IllegalArgumentException> { buildDay(settings, emptyList(), -(lastGoodN + 2)) }
    }

    @Test
    fun aNonPositiveRuntimeLeavesTheItemOutAndIsNotAnError() {
        val items = listOf(testItem(idOf('1'), runtimeMs = 0L), testItem(idOf('2'), runtimeMs = -5L))
        val day = buildDay(settings, items, 0L)
        assertEquals(1, day.blocks.size)
    }

    @Test
    fun twoToTheTwentySixItemsOrMoreAreRejectedBeforeAnythingIsRead() {
        val tooMany =
            object : AbstractList<ScheduleItem>() {
                override val size: Int = 1 shl 26

                override fun get(index: Int): ScheduleItem = error("the limit is checked before any item is read")
            }
        assertFailsWith<IllegalArgumentException> { buildDay(settings, tooMany, 0L) }
    }

    @Test
    fun aNegativeKIsAnError() {
        val day = buildDay(settings, emptyList(), 0L)
        assertFailsWith<IllegalArgumentException> { day.whatsOn(day.startMs, -1) }
    }
}
