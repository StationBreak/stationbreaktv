// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/**
 * The names of one scheduled item, for a banner that says what is on (spec 14). [title] is the item's own name;
 * [seriesName], [seasonNumber] and [episodeNumber] are set for an episode whose series is known, and null otherwise.
 * [year] is the item's production year as the server gave it (the one the info overlay shows); null when it has none.
 */
data class ProgramInfo(
    val id: CanonicalId,
    val kind: ScheduleItemKind,
    val title: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val year: Int? = null,
)
