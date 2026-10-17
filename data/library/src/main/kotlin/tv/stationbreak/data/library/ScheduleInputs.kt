// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * An item as one version of it held its schedule fields (spec 09, Unit 2): a current row or an `item_version` row, read
 * with the same columns so both feed one mapping.
 */
internal data class HeldRow(
    val itemId: String,
    val libraryId: String,
    val kind: String,
    val seriesId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runtimeMs: Long?,
    val createdAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val deletedAtMs: Long?,
    val seenFromMs: Long,
)

/** Why the as-of read left an item out, and the `ScheduleItem` check (or `buildDay` check) it mirrors (Vimes M3). */
internal enum class DropReason(
    val mirrors: String,
) {
    KindNotMovieOrEpisode("ScheduleItemKind has only Movie and Episode"),
    IdNotCanonical("CanonicalId.parse(id)"),
    SeriesIdNotCanonical("CanonicalId.parse(seriesId)"),
    RuntimeMissing("ScheduleItem.runtimeMs is a runtime: absent or not positive means no runtime (rules format)"),
    CreatedAtMissing("ScheduleItem.createdAtMs is a time: absent means none"),
    TimeOutOfRange("buildDay: createdAt and deletedAt within +-2^52 ms"),
    PremiereDateMalformed("ScheduleItem: premiereDate is YYYY-MM-DD, year 0001 to 9999"),
    ProductionYearOutOfRange("ScheduleItem: productionYear is 1 to 9999"),
    SeasonNegative("ScheduleItem: seasonNumber is not negative"),
    EpisodeNegative("ScheduleItem: episodeNumber is not negative"),
    MovieHasSeriesData("ScheduleItem: a movie has no series, season or episode number"),
    DuplicateId("EpochPool.build: an ID may be given only once"),
    RejectedByScheduleItem("ScheduleItem's own init refused what the checks above let through"),
}

/** One item turned into the scheduler's type, or the reason it was left out. */
internal sealed interface Converted {
    data class Accepted(
        val item: ScheduleItem,
    ) : Converted

    data class Dropped(
        val reason: DropReason,
    ) : Converted
}

/** What the as-of read returned, and what it left out by reason (diagnostics). */
internal class ScheduleRead(
    val items: List<ScheduleItem>,
    val dropped: Map<DropReason, Int>,
)

internal object ScheduleInputs {
    // RED STUB (spec 09 task 2.8): compiles the tests, fails them.
    fun convert(row: HeldRow): Converted = Converted.Dropped(DropReason.RejectedByScheduleItem)
}
