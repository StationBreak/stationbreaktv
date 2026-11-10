// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.distinctByFold

// What the channel builder's pickers read (spec 22): the current state of the saved library, never for building a day.
// No schema change and no index: the genres and tags of items are rows, those of series one packed column each.

/** A title found by name: its stored [id], its [name] and its production year, if it has one. */
internal class TitleRow(
    val id: String,
    val name: String,
    val year: Int?,
) {
    // A name is text a person typed: it never reaches a log.
    override fun toString(): String = "TitleRow(id=$id, name=<name>)"
}

/** A stored ID and the name it has. */
internal class NameRow(
    val id: String,
    val name: String,
) {
    // A name is text a person typed: it never reaches a log.
    override fun toString(): String = "NameRow(id=$id, name=<name>)"
}

/**
 * The `LIKE` pattern for a name that contains [text]: `\` is escaped first, then `%` and `_`, so none of them is a
 * wildcard, and the pattern is used with `ESCAPE '\'`. SQLite's `LIKE` is case-insensitive for ASCII only, which is
 * what `foldNameAscii` does to the names the rules match.
 */
internal fun likePattern(text: String): String {
    val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}

/**
 * The distinct genres of the live items and series, one per folded name (the fold the rules match by), by folded name.
 * A group is shown under its first spelling in plain `String` order: the reads are distinct texts, so a per-spelling
 * item count is not at hand.
 */
internal suspend fun distinctGenres(
    dao: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
): List<String> = packedNames(dao.liveGenreNames(server, user), series.liveGenreColumns(server, user))

/** The distinct tags of the live items and series, as [distinctGenres]. */
internal suspend fun distinctTags(
    dao: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
): List<String> = packedNames(dao.liveTagNames(server, user), series.liveTagColumns(server, user))

/** The distinct studios of the live items and series, as [distinctGenres]. */
internal suspend fun distinctStudios(
    dao: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
): List<String> = packedNames(dao.liveStudioNames(server, user), series.liveStudioColumns(server, user))

// A damaged packed column is skipped, never thrown: a picker lists what it can read.
private fun packedNames(
    rows: List<String>,
    packed: List<String>,
): List<String> {
    val names = HashSet<String>(rows)
    for (text in packed) {
        try {
            names += VersionCodec.decode(text)
        } catch (_: IllegalArgumentException) {
            continue
        }
    }
    return distinctByFold(names)
}

/** Up to [limit] live shows and films whose name contains [text], by name and then ID. */
@Suppress("LongParameterList") // The two DAOs, the account's keys and the query.
internal suspend fun searchTitles(
    dao: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
    text: String,
    limit: Int,
): List<TitleHit> {
    if (limit <= 0 || text.isEmpty()) return emptyList()
    val pattern = likePattern(text)
    val shows = series.searchSeries(server, user, pattern, limit).mapNotNull { it.hit(TitleKind.Show) }
    val films = dao.searchMovies(server, user, pattern, limit).mapNotNull { it.hit(TitleKind.Film) }
    return (shows + films).sortedWith(compareBy<TitleHit>({ it.name }, { it.id.value })).take(limit)
}

private fun TitleRow.hit(kind: TitleKind): TitleHit? {
    val canonical = CanonicalId.parseOrNull(id) ?: return null
    return TitleHit(canonical, name, kind, year)
}

/** The names of the live shows and films among [ids], read in chunks the platform SQLite can bind. */
internal suspend fun titleNames(
    dao: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
    ids: Set<CanonicalId>,
): Map<CanonicalId, String> {
    val names = HashMap<CanonicalId, String>()
    for (chunk in ids.map { it.value }.chunked(LibraryDao.MAX_VARIABLES)) {
        for (row in series.seriesNames(server, user, chunk) + dao.movieNames(server, user, chunk)) {
            CanonicalId.parseOrNull(row.id)?.let { names.putIfAbsent(it, row.name) }
        }
    }
    return names
}
