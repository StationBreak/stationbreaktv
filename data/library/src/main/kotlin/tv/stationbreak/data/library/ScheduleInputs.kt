// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.ScheduleItemProblem

/**
 * An item as one version of it held its schedule fields (spec 09, Unit 2): a current row or an `item_version` row, read
 * with the same columns so both feed one mapping. It carries the rating exactly as received (null, `""` and `" "` are
 * three values), the ordered genres and tags, and an episode's season (spec 10, Unit 1).
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
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val seasonId: String? = null,
)

/**
 * Why the as-of read left an item out, and the check it mirrors (Vimes M3). The reasons for the fields `ScheduleItem`
 * itself validates come from [ScheduleItem.problem] (see [of]), so the rules live in one place (Granny M1); the others
 * this layer's own: what is lost between a row and a `ScheduleItem` (a kind, an ID, a runtime, a time) and what only a
 * whole list shows (a duplicate ID).
 */
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
    ;

    companion object {
        /** The reason for a problem `ScheduleItem` found. Exhaustive, so a new problem is a compile error here. */
        fun of(problem: ScheduleItemProblem): DropReason =
            when (problem) {
                ScheduleItemProblem.PremiereDateMalformed -> PremiereDateMalformed
                ScheduleItemProblem.ProductionYearOutOfRange -> ProductionYearOutOfRange
                ScheduleItemProblem.SeasonNegative -> SeasonNegative
                ScheduleItemProblem.EpisodeNegative -> EpisodeNegative
                ScheduleItemProblem.MovieHasSeriesData -> MovieHasSeriesData
            }
    }
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

/**
 * What the as-of read returned, and what it left out by reason (diagnostics). Internal on purpose: the port
 * `LibraryRepository.scheduleItems` returns the items alone, and widens to carry the counts when diagnostics or F1.2's
 * rule engine need them (Granny L3).
 */
internal class ScheduleRead(
    val items: List<ScheduleItem>,
    val dropped: Map<DropReason, Int>,
)

/**
 * Turns the fields one version held into the scheduler's [ScheduleItem], or says why it can't (Vimes M3). The checks of
 * this layer run first, in the order of the list below, and the fields `ScheduleItem` validates are then asked of
 * [ScheduleItem.problem], which `ScheduleItem`'s own init also uses. An item is counted once, under the first reason
 * fits. The item is built by `ScheduleItem` itself, and anything its init still refuses is counted as
 * [DropReason.RejectedByScheduleItem] rather than thrown, so one bad item can never fail a whole day. That count should
 * stay 0; a test asserts it over the whole corpus.
 */
internal object ScheduleInputs {
    private const val MAX_TIME_MS = 1L shl 52

    /** A row with its kind and IDs parsed once, for the checks. */
    private class Candidate(
        val row: HeldRow,
    ) {
        val kind: ScheduleItemKind? = ScheduleItemKind.entries.firstOrNull { it.name == row.kind }
        val id: CanonicalId? = CanonicalId.parseOrNull(row.itemId)
        val series: CanonicalId? = row.seriesId?.let { CanonicalId.parseOrNull(it) }
    }

    private fun timeOutOfRange(row: HeldRow): Boolean =
        listOfNotNull(row.createdAtMs, row.deletedAtMs).any { it !in -MAX_TIME_MS..MAX_TIME_MS }

    private val checks: List<Pair<DropReason, (Candidate) -> Boolean>> =
        listOf(
            DropReason.KindNotMovieOrEpisode to { it.kind == null },
            DropReason.IdNotCanonical to { it.id == null },
            DropReason.SeriesIdNotCanonical to { it.row.seriesId != null && it.series == null },
            DropReason.RuntimeMissing to { (it.row.runtimeMs ?: 0L) <= 0L },
            DropReason.CreatedAtMissing to { it.row.createdAtMs == null },
            DropReason.TimeOutOfRange to { timeOutOfRange(it.row) },
        )

    fun convert(row: HeldRow): Converted {
        val candidate = Candidate(row)
        val own = checks.firstOrNull { (_, fails) -> fails(candidate) }?.first
        return if (own != null) Converted.Dropped(own) else build(candidate)
    }

    private fun build(candidate: Candidate): Converted {
        val row = candidate.row
        // The checks have passed, so nothing required is null; if one were, requireNotNull's refusal is caught below
        // like ScheduleItem's own, and the item is counted as rejected instead of crashing the read.
        return try {
            val kind = requireNotNull(candidate.kind)
            val problem =
                ScheduleItem.problem(
                    kind,
                    row.premiereDate,
                    row.productionYear,
                    candidate.series,
                    row.seasonNumber,
                    row.episodeNumber,
                )
            if (problem != null) return Converted.Dropped(DropReason.of(problem))
            Converted.Accepted(
                ScheduleItem(
                    id = requireNotNull(candidate.id),
                    kind = kind,
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
