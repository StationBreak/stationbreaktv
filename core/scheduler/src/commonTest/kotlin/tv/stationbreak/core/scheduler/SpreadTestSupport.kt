// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The regular layout of a hand-worked case: its blocks, the end of each run, and what it aired. */
internal class Laid(
    val blocks: List<ScheduleBlock>,
    val runEnds: List<Long>,
    val aired: List<AiredBefore>,
)

/**
 * Hand-worked cases of the spread end of the day, in whole minutes. Each item gets a label, and a day is described as
 * `start-end:label`, `break` or `slate`.
 */
internal class SpreadCases {
    private val labels = HashMap<CanonicalId, String>()
    private var serial = 0

    /** An item on its own (a film in no collection), [minutes] long. */
    fun single(
        label: String,
        minutes: Int,
    ): ScheduleItem {
        serial++
        val item = testItem(hexIdOf(0x100 + serial, 0), runtimeMs = minutes * MS_MINUTE, createdAtMs = LONG_AGO)
        return named(item, label)
    }

    /** Episode [number] of the series number [series], [minutes] long. */
    fun ep(
        label: String,
        series: Int,
        number: Int,
        minutes: Int,
    ): ScheduleItem = named(episodeOf(series, number, minutes), label)

    fun named(
        item: ScheduleItem,
        label: String,
    ): ScheduleItem {
        labels[item.id] = label
        return item
    }

    fun label(item: ScheduleItem): String = labels.getValue(item.id)

    /** The runs laid end to end from [startMinute], no grid. */
    fun laid(
        startMinute: Int,
        runs: List<List<ScheduleItem>>,
    ): Laid {
        val blocks = ArrayList<ScheduleBlock>()
        val ends = ArrayList<Long>()
        val aired = ArrayList<AiredBefore>()
        var t = startMinute * MS_MINUTE
        for (run in runs) {
            for (item in run) {
                blocks += ScheduleBlock(t, t + item.runtimeMs, ScheduleContent.Program(item.id))
                t += item.runtimeMs
                aired += AiredBefore(item, t)
            }
            ends += t
        }
        return Laid(blocks, ends, aired)
    }

    /**
     * The spread end of a day over `[startMinute, endMinute)`, checked to tile the window, with no slate unless on a
     * grid, and never a break straight after a break or a slate.
     */
    @Suppress("LongParameterList") // The case's inputs, one for one.
    fun spreadOf(
        settings: SchedulerSettings,
        laid: Laid,
        visit: List<ScheduleItem>,
        startMinute: Int,
        endMinute: Int,
        firstItem: Int = 0,
        airedBefore: List<AiredBefore> = emptyList(),
        groups: GroupRules = noGroups(),
    ): List<ScheduleBlock> {
        val start = startMinute * MS_MINUTE
        val end = endMinute * MS_MINUTE
        val aired = airedBefore + laid.aired
        val blocks = spread(settings, laid.blocks, laid.runEnds, visit, firstItem, aired, groups, start, end)
        assertTiles(blocks, start, end, settings.alignToGrid)
        return blocks
    }

    fun describe(blocks: List<ScheduleBlock>): List<String> =
        blocks.map { block ->
            val what =
                when (val content = block.content) {
                    is ScheduleContent.Program -> labels.getValue(content.itemId)
                    ScheduleContent.Slate -> "slate"
                    ScheduleContent.Break -> "break"
                }
            check(block.startMs % MS_MINUTE == 0L && block.endMs % MS_MINUTE == 0L) { "not on a whole minute: $block" }
            "${block.startMs / MS_MINUTE}-${block.endMs / MS_MINUTE}:$what"
        }

    /** The labels of the programmes of [blocks], in order. */
    fun programmes(blocks: List<ScheduleBlock>): List<String> =
        blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId?.let(labels::getValue) }
}

/** Settings of a spread day that starts at minute 0. */
internal fun spreadSettings(
    mode: OrderingMode,
    grid: Boolean = false,
    repeatGapMs: Long = 180 * MS_MINUTE,
    epochHours: Int = 24,
): SchedulerSettings =
    testSettings(
        orderingMode = mode,
        alignToGrid = grid,
        epochHours = epochHours,
        startMinuteOfDay = 0,
        repeatGapMs = repeatGapMs,
        leftover = LeftoverPolicy.Spread,
    )

/**
 * [blocks] cover exactly `[start, end)` end to end, with no slate unless [grid], and no break straight after a break
 * or a slate.
 */
internal fun assertTiles(
    blocks: List<ScheduleBlock>,
    start: Long,
    end: Long,
    grid: Boolean,
) {
    assertTrue(blocks.isNotEmpty(), "no blocks")
    assertEquals(start, blocks.first().startMs, "the first block starts the window")
    assertEquals(end, blocks.last().endMs, "the last block ends the window")
    for ((a, b) in blocks.zipWithNext()) {
        assertEquals(a.endMs, b.startMs, "contiguous")
        val afterGap = a.content == ScheduleContent.Break || a.content == ScheduleContent.Slate
        assertTrue(!(afterGap && b.content == ScheduleContent.Break), "a break straight after a break or slate")
    }
    if (!grid) assertTrue(blocks.none { it.content == ScheduleContent.Slate }, "a slate on a day off the grid")
}
