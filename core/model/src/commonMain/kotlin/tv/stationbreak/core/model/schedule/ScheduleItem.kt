// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId

/** What a scheduled item is. Only movies and episodes are items (docs/rules-format.md, "Items and the pool"). */
enum class ScheduleItemKind {
    Movie,
    Episode,
}

/**
 * One item offered to the scheduler. All times are milliseconds since 1970-01-01T00:00:00Z.
 *
 * @property runtimeMs the runtime. The pool leaves out an item with no runtime or one longer than the epoch.
 * @property createdAtMs the server's creation time.
 * @property deletedAtMs the client's first observation that the item is gone, or null while it is present.
 * @property premiereDate the premiere (air) date as `YYYY-MM-DD`, compared as text, or null.
 * @property seriesId the series of an episode; the group a chronological order keeps together.
 */
@Suppress("LongParameterList") // A plain data carrier: one field per input the ordering and the pool read.
data class ScheduleItem(
    val id: CanonicalId,
    val kind: ScheduleItemKind,
    val runtimeMs: Long,
    val createdAtMs: Long,
    val deletedAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val seriesId: CanonicalId?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
) {
    init {
        require(premiereDate == null || DATE.matches(premiereDate)) { "A premiere date is YYYY-MM-DD" }
        require(seasonNumber == null || seasonNumber >= 0) { "A season number is not negative" }
        require(episodeNumber == null || episodeNumber >= 0) { "An episode number is not negative" }
        require(kind != ScheduleItemKind.Movie || (seriesId == null && seasonNumber == null && episodeNumber == null)) {
            "A movie has no series, season or episode number"
        }
    }

    /**
     * The group a chronological order keeps together: the series of an episode, or the item's own ID for a movie and
     * for an episode with no series (a group of one).
     */
    val groupId: CanonicalId get() = seriesId ?: id

    private companion object {
        val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    }
}
