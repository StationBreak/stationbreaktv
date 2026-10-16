// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.testing.SyntheticLibrary

/**
 * The movies and episodes of a generated library as scheduler items (music videos and plain videos are not scheduled).
 * [runtimeOf] chooses each runtime in milliseconds, so a test can make programs shorter and the day longer in blocks.
 * A runtime of 0 (the library has no running time for about one item in fifty) is left out of the pool.
 */
internal fun syntheticScheduleItems(
    library: SyntheticLibrary,
    runtimeOf: (MediaItem) -> Long,
): List<ScheduleItem> =
    library.items
        .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
        .map { media ->
            val episode = media.kind == ItemKind.Episode
            ScheduleItem(
                id = CanonicalId.parse(media.id.value),
                kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                runtimeMs = runtimeOf(media),
                createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                deletedAtMs = null,
                premiereDate = null,
                productionYear = media.productionYear,
                seriesId = media.seriesId?.let { CanonicalId.parse(it.value) },
                seasonNumber = media.seasonNumber,
                episodeNumber = media.episodeNumber,
            )
        }.toList()

/** The library's own runtime in milliseconds, or 0 when it has none. */
internal fun MediaItem.runtimeMsOrZero(): Long = runtime?.inWholeMilliseconds ?: 0L

/** A runtime of 1 to 91 seconds taken from the library's own, so a 24-hour day holds about two thousand programs. */
internal fun MediaItem.shortRuntimeMs(): Long = 1_000L + runtimeMsOrZero() % 90_000L
