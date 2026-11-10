// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
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
 * One synthetic library of [size] items for the repair benchmark and the equality matrix: its [id], the [digit] each of
 * its item and series ids starts with (so ids never collide between libraries), the generator's [seed], and, when
 * [onlyGenre] is given, that one genre on every item. Synthetic data only.
 */
internal class SyntheticLib(
    val id: ItemId,
    val digit: Char,
    val size: Int,
    val seed: Long,
    val onlyGenre: String? = null,
)

/** A [CountingLibrary] for [ACCOUNT] holding every one of [libs], every item with a current row (so it is live). */
internal fun seededLibrary(libs: List<SyntheticLib>): CountingLibrary {
    val library = CountingLibrary()
    runBlocking { library.delegate.useAccount(ACCOUNT) }
    for (lib in libs) library.seedSynthetic(lib)
    return library
}

/** Seeds [lib]: its movies and episodes as rule items, their series, and a current row for each item. */
internal fun CountingLibrary.seedSynthetic(lib: SyntheticLib) {
    val synthetic = SyntheticLibrary(lib.size, seed = lib.seed, libraryId = lib.id)
    val media =
        synthetic.items
            .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
            .filter { (it.runtime?.inWholeMilliseconds ?: 0L) > 0L }
            .toList()
    val libraryId = CanonicalId.parse(lib.id.value)
    seed(media.map { ruleItemOf(it, lib, libraryId) })
    val bySeries = media.filter { it.seriesId != null }.groupBy { idOf(checkNotNull(it.seriesId), lib) }
    delegate.seedSeries(ACCOUNT, *bySeries.map { (id, its) -> seriesOf(id, its.first(), lib) }.toTypedArray())
    // Every item has a current row, so it is live.
    val rows = media.map { MediaItem(ItemId(idOf(it.id, lib).value), "Synthetic", ItemKind.Movie, null, null) }
    delegate.seed(ACCOUNT, lib.id, *rows.toTypedArray())
}

// A synthetic id with the library's digit first: the libraries' own ids all start the same way.
private fun idOf(
    id: ItemId,
    lib: SyntheticLib,
): CanonicalId = CanonicalId.parse(lib.digit + id.value.drop(1))

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
                seriesId = media.seriesId?.let { idOf(it, lib) },
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
