// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject

/** The kinds of item `filters.kind` can pick, with the file's exact names. */
enum class FilterKind(
    val wireName: String,
) {
    Movie("movie"),
    Episode("episode"),
}

/**
 * The `filters` object of format versions 2 and 3 (docs/rules-format.md, "Filters"). Every part is optional, and a part
 * that is present must pass for an item to stay in the pool. `years` and `runtime` are objects in the file, so each of
 * their pairs is set together; [yearsExtras] and [runtimeExtras] keep the fields those two objects do not know.
 *
 * Version 3 adds [tags], [studios], [notGenres] and a runtime window for each kind of item: `runtime.episode` and
 * `runtime.movie`, whose own unknown fields are in [episodeRuntimeExtras] and [movieRuntimeExtras]. In a version 2 file
 * those names are unknown fields, so they are in the `extras` and are not these properties.
 */
data class Filters(
    val genres: List<String>?,
    val yearFrom: Int?,
    val yearTo: Int?,
    val minRuntimeMinutes: Int?,
    val maxRuntimeMinutes: Int?,
    val kind: FilterKind?,
    val extras: JsonObject = NO_EXTRAS,
    val yearsExtras: JsonObject = NO_EXTRAS,
    val runtimeExtras: JsonObject = NO_EXTRAS,
    val tags: List<String>? = null,
    val studios: List<String>? = null,
    val notGenres: List<String>? = null,
    val episodeMinRuntimeMinutes: Int? = null,
    val episodeMaxRuntimeMinutes: Int? = null,
    val movieMinRuntimeMinutes: Int? = null,
    val movieMaxRuntimeMinutes: Int? = null,
    val episodeRuntimeExtras: JsonObject = NO_EXTRAS,
    val movieRuntimeExtras: JsonObject = NO_EXTRAS,
) {
    init {
        for ((names, what) in listOf(genres to "genre", tags to "tag", studios to "studio", notGenres to "genre")) {
            names?.let { list ->
                require(list.size in 1..RulesLimits.MAX_LIST_ENTRIES) { "There are 1 to 256 names in a filter list" }
                for (name in list) RulesVocabulary.requireText(name, RulesLimits.MAX_TEXT_CODE_POINTS, "A $what")
            }
        }
        require((yearFrom == null) == (yearTo == null)) { "The years need both a first and a last" }
        if (yearFrom != null && yearTo != null) {
            require(yearFrom in RulesVocabulary.MIN_YEAR..RulesVocabulary.MAX_YEAR) { "A year is from 1 to 9999" }
            require(yearTo in yearFrom..RulesVocabulary.MAX_YEAR) { "The last year is from the first year to 9999" }
        }
        requireWindow(minRuntimeMinutes, maxRuntimeMinutes)
        requireWindow(episodeMinRuntimeMinutes, episodeMaxRuntimeMinutes)
        requireWindow(movieMinRuntimeMinutes, movieMaxRuntimeMinutes)
        require(yearsExtras.isEmpty() || yearFrom != null) { "Unknown year fields need years" }
        require(runtimeExtras.isEmpty() || hasAnyRuntime) { "Unknown runtime fields need a runtime" }
        require(episodeRuntimeExtras.isEmpty() || hasEpisodeRuntime) { "Unknown episode runtime fields need a window" }
        require(movieRuntimeExtras.isEmpty() || hasMovieRuntime) { "Unknown movie runtime fields need a window" }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.FILTERS + usedNames())
        RulesVocabulary.requireFreeExtras(yearsExtras, RulesVocabulary.FILTER_YEARS)
        RulesVocabulary.requireFreeExtras(runtimeExtras, RulesVocabulary.FILTER_RUNTIME + usedRuntimeNames())
        RulesVocabulary.requireFreeExtras(episodeRuntimeExtras, RulesVocabulary.FILTER_RUNTIME)
        RulesVocabulary.requireFreeExtras(movieRuntimeExtras, RulesVocabulary.FILTER_RUNTIME)
    }

    private fun requireWindow(
        min: Int?,
        max: Int?,
    ) {
        val minutes = 1..RulesVocabulary.MAX_RUNTIME_MINUTES
        require(min == null || min in minutes) { "A runtime bound is 1 to 1440 minutes" }
        require(max == null || max in minutes) { "A runtime bound is 1 to 1440 minutes" }
        if (min != null && max != null) require(min <= max) { "The longest runtime is not below the shortest" }
    }

    // The version 3 names this object holds, which an unknown field must not reuse.
    private fun usedNames(): Set<String> =
        buildSet {
            if (tags != null) add("tags")
            if (studios != null) add("studios")
            if (notGenres != null) add("notGenres")
        }

    private fun usedRuntimeNames(): Set<String> =
        buildSet {
            if (hasEpisodeRuntime) add("episode")
            if (hasMovieRuntime) add("movie")
        }

    private val hasRuntime: Boolean get() = minRuntimeMinutes != null || maxRuntimeMinutes != null

    private val hasEpisodeRuntime: Boolean get() = episodeMinRuntimeMinutes != null || episodeMaxRuntimeMinutes != null

    private val hasMovieRuntime: Boolean get() = movieMinRuntimeMinutes != null || movieMaxRuntimeMinutes != null

    /** True when a runtime window for one kind of item is set (version 3). */
    val hasKindRuntime: Boolean get() = hasEpisodeRuntime || hasMovieRuntime

    /** True when the `runtime` object is written: a general window or a window for a kind. */
    val hasAnyRuntime: Boolean get() = hasRuntime || hasEpisodeRuntime || hasMovieRuntime

    /** True when a part of format version 3 is set: the file must say 3. */
    val usesVersion3: Boolean
        get() = tags != null || studios != null || notGenres != null || hasEpisodeRuntime || hasMovieRuntime

    /** True when no part is set: the object changes nothing. */
    val isEmpty: Boolean
        get() = genres == null && yearFrom == null && !hasAnyRuntime && kind == null && !usesVersion3
}
