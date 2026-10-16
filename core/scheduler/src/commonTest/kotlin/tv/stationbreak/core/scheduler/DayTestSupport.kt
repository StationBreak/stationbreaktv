// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** A movie whose ID is 32 copies of [digit] and whose runtime is [minutes]. Created long ago, so the pool keeps it. */
internal fun movie(
    digit: Char,
    minutes: Int,
) = testItem(idOf(digit), runtimeMs = minutes * MS_MINUTE, createdAtMs = LONG_AGO)

/** An episode of the series [series] (a digit standing for its ID). */
internal fun episode(
    digit: Char,
    series: Char,
    season: Int,
    number: Int,
    minutes: Int,
) = ScheduleItem(
    id = idOf(digit),
    kind = ScheduleItemKind.Episode,
    runtimeMs = minutes * MS_MINUTE,
    createdAtMs = LONG_AGO,
    deletedAtMs = null,
    premiereDate = null,
    productionYear = null,
    seriesId = idOf(series),
    seasonNumber = season,
    episodeNumber = number,
)

internal const val LONG_AGO = -1_000_000_000_000L

/**
 * A day in whole minutes since the Unix epoch, one string per block: `start-end:X`, where X is the first digit of the
 * item's ID or `slate`. Every hand-worked case in these tests starts and ends on a whole minute.
 */
internal fun Day.describe(): List<String> =
    blocks.map { block ->
        val what =
            when (val content = block.content) {
                is ScheduleContent.Program -> {
                    content.itemId.value
                        .first()
                        .toString()
                }

                ScheduleContent.Slate -> {
                    "slate"
                }
            }
        check(block.startMs % MS_MINUTE == 0L && block.endMs % MS_MINUTE == 0L) { "not on a whole minute: $block" }
        "${block.startMs / MS_MINUTE}-${block.endMs / MS_MINUTE}:$what"
    }
