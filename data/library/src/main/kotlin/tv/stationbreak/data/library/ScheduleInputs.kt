// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

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

/**
 * Turns the fields one version held into the scheduler's [ScheduleItem], or says why it can't (Vimes M3). Each check is
 * the one `ScheduleItem`, `CanonicalId` or `buildDay` makes, written out so the reason has a name, and they run in the
 * order of the check list: an item is counted once, under the first reason that fits. The item is then built by
 * `ScheduleItem` itself, and anything its own validation still refuses is counted as
 * [DropReason.RejectedByScheduleItem] rather than thrown, so one bad item can never fail a whole day.
 */
internal object ScheduleInputs {
    private const val MAX_TIME_MS = 1L shl 52
    private const val MAX_YEAR = 9999
    private val DATE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")

    /** A row with its kind and IDs parsed once, for the checks. */
    private class Candidate(
        val row: HeldRow,
    ) {
        val kind: ScheduleItemKind? = ScheduleItemKind.entries.firstOrNull { it.name == row.kind }
        val id: CanonicalId? = CanonicalId.parseOrNull(row.itemId)
        val series: CanonicalId? = row.seriesId?.let { CanonicalId.parseOrNull(it) }
    }

    // ScheduleItem's own date check: four digits, a month 01-12, a day 01-31, and a year other than 0000.
    private fun validDate(text: String): Boolean = DATE.matches(text) && !text.startsWith("0000-")

    private fun timeOutOfRange(row: HeldRow): Boolean =
        listOfNotNull(row.createdAtMs, row.deletedAtMs).any { it !in -MAX_TIME_MS..MAX_TIME_MS }

    private fun movieWithSeriesData(candidate: Candidate): Boolean =
        candidate.kind == ScheduleItemKind.Movie &&
            listOfNotNull(candidate.row.seriesId, candidate.row.seasonNumber, candidate.row.episodeNumber).isNotEmpty()

    private val checks: List<Pair<DropReason, (Candidate) -> Boolean>> =
        listOf(
            DropReason.KindNotMovieOrEpisode to { it.kind == null },
            DropReason.IdNotCanonical to { it.id == null },
            DropReason.SeriesIdNotCanonical to { it.row.seriesId != null && it.series == null },
            DropReason.RuntimeMissing to { (it.row.runtimeMs ?: 0L) <= 0L },
            DropReason.CreatedAtMissing to { it.row.createdAtMs == null },
            DropReason.TimeOutOfRange to { timeOutOfRange(it.row) },
            DropReason.PremiereDateMalformed to { it.row.premiereDate?.let(::validDate) == false },
            DropReason.ProductionYearOutOfRange to { it.row.productionYear?.let { y -> y !in 1..MAX_YEAR } == true },
            DropReason.SeasonNegative to { (it.row.seasonNumber ?: 0) < 0 },
            DropReason.EpisodeNegative to { (it.row.episodeNumber ?: 0) < 0 },
            DropReason.MovieHasSeriesData to ::movieWithSeriesData,
        )

    fun convert(row: HeldRow): Converted {
        val candidate = Candidate(row)
        val reason = checks.firstOrNull { (_, fails) -> fails(candidate) }?.first
        return if (reason != null) Converted.Dropped(reason) else build(candidate)
    }

    private fun build(candidate: Candidate): Converted {
        val row = candidate.row
        // The checks have passed, so nothing required is null; if one were, requireNotNull's refusal is caught below
        // like ScheduleItem's own, and the item is counted as rejected instead of crashing the read.
        return try {
            Converted.Accepted(
                ScheduleItem(
                    id = requireNotNull(candidate.id),
                    kind = requireNotNull(candidate.kind),
                    runtimeMs = requireNotNull(row.runtimeMs),
                    createdAtMs = requireNotNull(row.createdAtMs),
                    deletedAtMs = row.deletedAtMs,
                    premiereDate = row.premiereDate,
                    productionYear = row.productionYear,
                    seriesId = candidate.series,
                    seasonNumber = row.seasonNumber,
                    episodeNumber = row.episodeNumber,
                ),
            )
        } catch (
            @Suppress("SwallowedException") refused: IllegalArgumentException,
        ) {
            Converted.Dropped(DropReason.RejectedByScheduleItem)
        }
    }
}
