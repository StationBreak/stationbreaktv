// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId

/** What a scheduled item is. Only movies and episodes are items (docs/rules-format.md, "Items and the pool"). */
enum class ScheduleItemKind {
    Movie,
    Episode,
}

/** What is wrong with a [ScheduleItem]'s fields: the checks its `init` makes, named. See [ScheduleItem.problem]. */
enum class ScheduleItemProblem(
    val message: String,
) {
    PremiereDateMalformed("A premiere date is YYYY-MM-DD, with a year from 0001 to 9999"),
    ProductionYearOutOfRange("A production year is 1 to 9999"),
    SeasonNegative("A season number is not negative"),
    EpisodeNegative("An episode number is not negative"),
    MovieHasSeriesData("A movie has no series, season or episode number"),
}

/**
 * One item offered to the scheduler. All times are milliseconds since 1970-01-01T00:00:00Z.
 *
 * @property runtimeMs the runtime. The pool leaves out an item with no runtime or one longer than the epoch.
 * @property createdAtMs the server's creation time.
 * @property deletedAtMs the client's first observation that the item is gone, or null while it is present.
 * @property premiereDate the premiere (air) date as a UTC calendar date `YYYY-MM-DD`, compared as text, or null.
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
        val found = problem(kind, premiereDate, productionYear, seriesId, seasonNumber, episodeNumber)
        require(found == null) { found?.message.orEmpty() }
    }

    /**
     * The group a chronological order keeps together: the series of an episode, or the item's own ID for a movie and
     * for an episode with no series (a group of one).
     */
    val groupId: CanonicalId get() = seriesId ?: id

    companion object {
        /**
         * The first thing wrong with these fields, in the order the checks run, or null. It never throws: `init`
         * requires it to find nothing, and a caller that must not throw (the library cache's as-of read) asks it
         * first, so the rules live in one place.
         */
        fun problem(
            kind: ScheduleItemKind,
            premiereDate: String?,
            productionYear: Int?,
            seriesId: CanonicalId?,
            seasonNumber: Int?,
            episodeNumber: Int?,
        ): ScheduleItemProblem? =
            when {
                premiereDate != null && !validDate(premiereDate) -> {
                    ScheduleItemProblem.PremiereDateMalformed
                }

                productionYear != null && productionYear !in 1..MAX_YEAR -> {
                    ScheduleItemProblem.ProductionYearOutOfRange
                }

                seasonNumber.isNegative() -> {
                    ScheduleItemProblem.SeasonNegative
                }

                episodeNumber.isNegative() -> {
                    ScheduleItemProblem.EpisodeNegative
                }

                kind == ScheduleItemKind.Movie && listOfNotNull(seriesId, seasonNumber, episodeNumber).isNotEmpty() -> {
                    ScheduleItemProblem.MovieHasSeriesData
                }

                else -> {
                    null
                }
            }

        private fun validDate(text: String): Boolean = DATE.matches(text) && !text.startsWith("0000-")

        private fun Int?.isNegative(): Boolean = this != null && this < 0

        private const val MAX_YEAR = 9999
        private val DATE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
    }
}
