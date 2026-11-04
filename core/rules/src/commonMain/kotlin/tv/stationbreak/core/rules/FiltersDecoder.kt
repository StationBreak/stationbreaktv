// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val MAX_YEAR = RulesVocabulary.MAX_YEAR
private const val MAX_MINUTES = RulesVocabulary.MAX_RUNTIME_MINUTES

/**
 * Reads the `filters` object of a file of format [version] (2 or 3). The parts that version 3 adds are read only when
 * [version] is 3; in a version 2 file they are unknown fields. An unknown `kind` is recorded in [unsupported].
 */
internal class FiltersDecoder(
    private val unsupported: UnsupportedKinds,
    private val version: Int,
) {
    private val v3 = version >= RulesVocabulary.VERSION_3

    private companion object {
        val NO_LISTS = Lists(null, null, null)
    }

    fun filters(element: JsonElement): Filters? {
        val obj = element as? JsonObject ?: invalid("filters must be an object")
        val genres = obj["genres"]?.let { names(it, "filters.genres") }
        val lists = if (v3) versionThreeLists(obj) else NO_LISTS
        val years = obj["years"]?.let { years(it) }
        val runtime = obj["runtime"]?.let { runtime(it) }
        val kind = obj["kind"]?.let { kind(it) }
        val extras = extras(obj, RulesVocabulary.filters(version))
        // An unknown kind makes the file unsupported, but only after every other part has been checked.
        return if (obj["kind"] != null && kind == null) {
            null
        } else {
            Filters(
                genres = genres,
                yearFrom = years?.from,
                yearTo = years?.to,
                minRuntimeMinutes = runtime?.min,
                maxRuntimeMinutes = runtime?.max,
                kind = kind,
                extras = extras,
                yearsExtras = years?.extras ?: NO_EXTRAS,
                runtimeExtras = runtime?.extras ?: NO_EXTRAS,
                tags = lists.tags,
                studios = lists.studios,
                notGenres = lists.notGenres,
                episodeMinRuntimeMinutes = runtime?.episode?.min,
                episodeMaxRuntimeMinutes = runtime?.episode?.max,
                movieMinRuntimeMinutes = runtime?.movie?.min,
                movieMaxRuntimeMinutes = runtime?.movie?.max,
                episodeRuntimeExtras = runtime?.episode?.extras ?: NO_EXTRAS,
                movieRuntimeExtras = runtime?.movie?.extras ?: NO_EXTRAS,
            )
        }
    }

    private class Lists(
        val notGenres: List<String>?,
        val tags: List<String>?,
        val studios: List<String>?,
    )

    private fun versionThreeLists(obj: JsonObject): Lists =
        Lists(
            notGenres = obj["notGenres"]?.let { names(it, "filters.notGenres") },
            tags = obj["tags"]?.let { names(it, "filters.tags") },
            studios = obj["studios"]?.let { names(it, "filters.studios") },
        )

    private fun names(
        element: JsonElement,
        path: String,
    ): List<String> {
        val array = element as? JsonArray ?: invalid("$path must be a list")
        if (array.isEmpty()) invalid("$path must have at least 1 entry")
        if (array.size > RulesLimits.MAX_LIST_ENTRIES) invalid("$path has more than 256 entries")
        return array.mapIndexed { i, item -> name(item, "$path[$i]", RulesLimits.MAX_TEXT_CODE_POINTS) }
    }

    private fun kind(element: JsonElement): FilterKind? {
        val text = string(element, "filters.kind")
        val kind = FilterKind.entries.firstOrNull { it.wireName == text }
        if (kind == null) unsupported.mark("filter kind", text)
        return kind
    }

    private class Years(
        val from: Int,
        val to: Int,
        val extras: JsonObject,
    )

    private class Runtime(
        val min: Int?,
        val max: Int?,
        val extras: JsonObject,
        val episode: Runtime? = null,
        val movie: Runtime? = null,
    ) {
        val isEmpty: Boolean get() = min == null && max == null
    }

    private fun years(element: JsonElement): Years {
        val obj = element as? JsonObject ?: invalid("filters.years must be an object")
        val path = "filters.years"
        val from = ranged(required(obj, "from", path), "$path.from", RulesVocabulary.MIN_YEAR, MAX_YEAR)
        val to = ranged(required(obj, "to", path), "$path.to", RulesVocabulary.MIN_YEAR, MAX_YEAR)
        if (to < from) invalid("filters.years.to must not be lower than filters.years.from")
        return Years(from, to, extras(obj, RulesVocabulary.FILTER_YEARS))
    }

    private fun runtime(element: JsonElement): Runtime {
        val obj = element as? JsonObject ?: invalid("filters.runtime must be an object")
        val general = window(obj, "filters.runtime", RulesVocabulary.filterRuntime(version))
        val episode = if (v3) obj["episode"]?.let { kindWindow(it, "filters.runtime.episode") } else null
        val movie = if (v3) obj["movie"]?.let { kindWindow(it, "filters.runtime.movie") } else null
        // In version 3 a window for one kind is a runtime on its own; in version 2 the general window is required.
        if (general.isEmpty && episode == null && movie == null) {
            invalid("filters.runtime needs minMinutes or maxMinutes")
        }
        return Runtime(general.min, general.max, general.extras, episode, movie)
    }

    private fun kindWindow(
        element: JsonElement,
        path: String,
    ): Runtime {
        val obj = element as? JsonObject ?: invalid("$path must be an object")
        val result = window(obj, path, RulesVocabulary.FILTER_RUNTIME)
        if (result.isEmpty) invalid("$path needs minMinutes or maxMinutes")
        return result
    }

    private fun window(
        obj: JsonObject,
        path: String,
        known: Set<String>,
    ): Runtime {
        val min = obj["minMinutes"]?.let { ranged(it, "$path.minMinutes", 1, MAX_MINUTES) }
        val max = obj["maxMinutes"]?.let { ranged(it, "$path.maxMinutes", 1, MAX_MINUTES) }
        if (min != null && max != null && min > max) invalid("$path.maxMinutes must not be below minMinutes")
        return Runtime(min, max, extras(obj, known))
    }
}
