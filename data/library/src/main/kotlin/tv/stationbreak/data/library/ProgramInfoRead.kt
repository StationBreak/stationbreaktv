// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/**
 * The ids among [ids] that have a live row (spec 14), in the same chunks, with no join and no names. An ID the
 * scheduler would not take is left out.
 */
internal suspend fun LibraryDao.currentIds(
    server: String,
    user: String,
    ids: Set<CanonicalId>,
): Set<CanonicalId> {
    val live = HashSet<CanonicalId>()
    for (chunk in ids.map { it.value }.chunked(LibraryDao.MAX_VARIABLES)) {
        liveAmong(server, user, chunk).mapNotNullTo(live) { CanonicalId.parseOrNull(it) }
    }
    return live
}

/**
 * The names of the live movies and episodes among [ids] (spec 14), read in chunks the platform SQLite can bind. A row
 * whose ID or kind the scheduler would not take is left out.
 */
internal suspend fun LibraryDao.programInfo(
    server: String,
    user: String,
    ids: Set<CanonicalId>,
): Map<CanonicalId, ProgramInfo> {
    val info = HashMap<CanonicalId, ProgramInfo>()
    for (chunk in ids.map { it.value }.chunked(LibraryDao.MAX_VARIABLES)) {
        for (row in programRows(server, user, chunk)) {
            val id = CanonicalId.parseOrNull(row.itemId)
            val kind = ScheduleItemKind.entries.firstOrNull { it.name == row.kind }
            if (id == null || kind == null) continue
            info[id] = ProgramInfo(id, kind, row.name, row.seriesName, row.seasonNumber, row.episodeNumber)
        }
    }
    return info
}
