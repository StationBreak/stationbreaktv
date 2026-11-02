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
 * The `filters` object of format version 2 (docs/rules-format.md, "Filters"). Every part is optional, and a part that
 * is present must pass for an item to stay in the pool. `years` and `runtime` are objects in the file, so each of their
 * pairs is set together; [yearsExtras] and [runtimeExtras] keep the fields those two objects do not know.
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
) {
    init {
        genres?.let { names ->
            require(names.size in 1..RulesLimits.MAX_LIST_ENTRIES) { "There are 1 to 256 genres" }
            for (name in names) RulesVocabulary.requireText(name, RulesLimits.MAX_TEXT_CODE_POINTS, "A genre")
        }
        require((yearFrom == null) == (yearTo == null)) { "The years need both a first and a last" }
        if (yearFrom != null && yearTo != null) {
            require(yearFrom in RulesVocabulary.MIN_YEAR..RulesVocabulary.MAX_YEAR) { "A year is from 1 to 9999" }
            require(yearTo in yearFrom..RulesVocabulary.MAX_YEAR) { "The last year is from the first year to 9999" }
        }
        val minutes = 1..RulesVocabulary.MAX_RUNTIME_MINUTES
        require(minRuntimeMinutes == null || minRuntimeMinutes in minutes) { "A runtime bound is 1 to 1440 minutes" }
        require(maxRuntimeMinutes == null || maxRuntimeMinutes in minutes) { "A runtime bound is 1 to 1440 minutes" }
        if (minRuntimeMinutes != null && maxRuntimeMinutes != null) {
            require(minRuntimeMinutes <= maxRuntimeMinutes) { "The longest runtime is not below the shortest" }
        }
        require(yearsExtras.isEmpty() || yearFrom != null) { "Unknown year fields need years" }
        require(runtimeExtras.isEmpty() || hasRuntime) { "Unknown runtime fields need a runtime" }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.FILTERS)
        RulesVocabulary.requireFreeExtras(yearsExtras, RulesVocabulary.FILTER_YEARS)
        RulesVocabulary.requireFreeExtras(runtimeExtras, RulesVocabulary.FILTER_RUNTIME)
    }

    private val hasRuntime: Boolean get() = minRuntimeMinutes != null || maxRuntimeMinutes != null

    /** True when no part is set: the object changes nothing. */
    val isEmpty: Boolean
        get() = genres == null && yearFrom == null && !hasRuntime && kind == null
}
