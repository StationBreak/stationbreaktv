// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** A canonical ID of 32 copies of the hex digit [digit]. */
internal fun idOf(digit: Char): CanonicalId = CanonicalId.parse(digit.toString().repeat(32))

/** Test helper: an item whose only interesting fields are its ID, runtime and the two moments the pool reads. */
@Suppress("LongParameterList")
internal fun testItem(
    id: CanonicalId,
    runtimeMs: Long = 30 * MS_MINUTE,
    createdAtMs: Long = 0L,
    deletedAtMs: Long? = null,
    kind: ScheduleItemKind = ScheduleItemKind.Movie,
) = ScheduleItem(
    id = id,
    kind = kind,
    runtimeMs = runtimeMs,
    createdAtMs = createdAtMs,
    deletedAtMs = deletedAtMs,
    premiereDate = null,
    productionYear = null,
    seriesId = null,
    seasonNumber = null,
    episodeNumber = null,
)
