// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

private const val HEX = 16
private const val HALF = 16

/** A canonical ID made from two numbers: [high] in the first 16 hex digits, [low] in the last 16. */
internal fun hexIdOf(
    high: Int,
    low: Int,
): CanonicalId = CanonicalId.parse(high.toString(HEX).padStart(HALF, '0') + low.toString(HEX).padStart(HALF, '0'))

/** The ID of the series number [series] (1 or more). */
internal fun seriesIdOf(series: Int): CanonicalId = hexIdOf(series, 0)

/** The ID of film number [film]: after every series ID. */
internal fun filmIdOf(film: Int): CanonicalId = hexIdOf(0x7000_0000 + film, 0)

/** Episode [number] (1 or more) of the series number [series], season 1, [minutes] long, created long ago. */
internal fun episodeOf(
    series: Int,
    number: Int,
    minutes: Int,
): ScheduleItem =
    ScheduleItem(
        id = hexIdOf(series, number),
        kind = ScheduleItemKind.Episode,
        runtimeMs = minutes * MS_MINUTE,
        createdAtMs = LONG_AGO,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = null,
        seriesId = seriesIdOf(series),
        seasonNumber = 1,
        episodeNumber = number,
    )

/** Film number [film], [minutes] long, created long ago. */
internal fun filmOf(
    film: Int,
    minutes: Int,
    year: Int? = null,
): ScheduleItem =
    ScheduleItem(
        id = filmIdOf(film),
        kind = ScheduleItemKind.Movie,
        runtimeMs = minutes * MS_MINUTE,
        createdAtMs = LONG_AGO,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = year,
        seriesId = null,
        seasonNumber = null,
        episodeNumber = null,
    )

/** The items of [runs], in order. */
internal fun List<Run>.flat(): List<ScheduleItem> = flatMap { it.items }

/** Seeds from 1 up whose key for [group] gives the queue start [start] in a queue of [size]. */
internal fun seedStartingAt(
    group: CanonicalId,
    size: Int,
    start: Int,
): ULong {
    var seed = 1uL
    while ((Orderings.shuffleKey(seed, group) % size.toULong()).toInt() != start) seed++
    return seed
}
