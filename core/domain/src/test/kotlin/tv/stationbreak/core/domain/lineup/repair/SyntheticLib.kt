// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.testing.SyntheticLibrary

/**
 * One synthetic library of [size] items for the repair benchmark and the equality matrix: its [id], the [digit] each
 * of its item and series ids starts with (so ids never collide between libraries), the generator's [seed], and, when
 * [onlyGenre] is given, that one genre on every item. Synthetic data only.
 *
 * The matrix's libraries set more: [betweenCutoffs] gives some items a creation time, and some a deletion time,
 * between two day cut-offs of the matrix's lineups, so a snapshot read at the earlier cut-off holds other items than
 * one read at the later; and [relisting] makes this library's episodes name the series of another library (a series
 * shared across libraries). One item id cannot be listed by two libraries in a snapshot (the rule evaluator refuses
 * a duplicate id, and the Room read keeps one row per item under its owner), so items an owner change moves between
 * libraries and the spellings of ids are covered at the data layer (`RuleSnapshotReferenceEqualityTest`).
 */
@Suppress("LongParameterList") // A library's setup, one for one; all but the first four defaulted.
internal class SyntheticLib(
    val id: ItemId,
    val digit: Char,
    val size: Int,
    val seed: Long,
    val onlyGenre: String? = null,
    val betweenCutoffs: Boolean = false,
    val relisting: SyntheticLib? = null,
)

/**
 * Between the matrix's two cut-offs: a day's cut-off is its start less the six hours' grace, so the days that start at
 * 06:00 and at 18:00 the day before read at 00:00 on the day and at noon the day before, and this is between them.
 */
internal val BETWEEN_CUTOFFS_MS: Long = NOON.toEpochMilliseconds() - 18 * 3_600_000L

/** A [CountingLibrary] for [ACCOUNT] holding every one of [libs], every item with a current row (so it is live). */
internal fun seededLibrary(libs: List<SyntheticLib>): CountingLibrary {
    val library = CountingLibrary()
    runBlocking { library.delegate.useAccount(ACCOUNT) }
    for (lib in libs) library.seedSynthetic(lib)
    return library
}

/** Seeds [lib]: its movies and episodes as rule items, their series, and a current row for each item. */
internal fun CountingLibrary.seedSynthetic(lib: SyntheticLib) {
    val media = mediaOf(lib)
    val libraryId = CanonicalId.parse(lib.id.value)
    val items = media.mapIndexed { index, item -> timed(ruleItemOf(item, lib, libraryId), lib, index) }
    seed(items)
    val bySeries = media.filter { it.seriesId != null }.groupBy { seriesIdOf(checkNotNull(it.seriesId), lib) }
    // A library that relists names the other library's series, which that library seeds: not seeded twice.
    if (lib.relisting == null) {
        delegate.seedSeries(ACCOUNT, *bySeries.map { (id, its) -> seriesOf(id, its.first(), lib) }.toTypedArray())
    }
    // Every item has a current row, so it is live.
    val rows = media.map { MediaItem(ItemId(idOf(it.id, lib).value), "Synthetic", ItemKind.Movie, null, null) }
    delegate.seed(ACCOUNT, lib.id, *rows.toTypedArray())
}

// The movies and episodes of [lib] that have a running time.
private fun mediaOf(lib: SyntheticLib): List<MediaItem> =
    SyntheticLibrary(lib.size, seed = lib.seed, libraryId = lib.id)
        .items
        .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
        .filter { (it.runtime?.inWholeMilliseconds ?: 0L) > 0L }
        .toList()

// A third of the items created between the cut-offs, another one in thirteen deleted between them, when [lib] asks.
private fun timed(
    item: RuleItem,
    lib: SyntheticLib,
    index: Int,
): RuleItem =
    when {
        !lib.betweenCutoffs -> item
        index % CREATED_EVERY == 0 -> item.copy(item = item.item.copy(createdAtMs = BETWEEN_CUTOFFS_MS))
        index % DELETED_EVERY == 0 -> item.copy(item = item.item.copy(deletedAtMs = BETWEEN_CUTOFFS_MS))
        else -> item
    }

// A synthetic id with the library's digit first: the libraries' own ids all start the same way.
private fun idOf(
    id: ItemId,
    lib: SyntheticLib,
): CanonicalId = CanonicalId.parse(lib.digit + id.value.drop(1))

// The series id an episode of [lib] names: its own library's, or the library it relists's, so the two share it.
private fun seriesIdOf(
    id: ItemId,
    lib: SyntheticLib,
): CanonicalId = idOf(id, lib.relisting ?: lib)

private fun genresOf(
    media: MediaItem,
    lib: SyntheticLib,
): List<String> = lib.onlyGenre?.let { listOf(it) } ?: media.genres

private fun ruleItemOf(
    media: MediaItem,
    lib: SyntheticLib,
    libraryId: CanonicalId,
): RuleItem {
    val episode = media.kind == ItemKind.Episode
    return RuleItem(
        item =
            ScheduleItem(
                id = idOf(media.id, lib),
                kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                runtimeMs = media.runtime?.inWholeMilliseconds ?: 0L,
                createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                deletedAtMs = null,
                premiereDate = null,
                productionYear = media.productionYear,
                seriesId = media.seriesId?.let { seriesIdOf(it, lib) },
                seasonNumber = media.seasonNumber,
                episodeNumber = media.episodeNumber,
            ),
        libraryId = libraryId,
        seasonId = null,
        officialRating = media.officialRating,
        genres = genresOf(media, lib),
        tags = media.tags,
    )
}

// A series takes its first episode's rating, genres and tags.
private fun seriesOf(
    id: CanonicalId,
    first: MediaItem,
    lib: SyntheticLib,
) = RuleSeries(id, first.officialRating, genresOf(first, lib), first.tags)

private const val CREATED_EVERY = 3
private const val DELETED_EVERY = 13
