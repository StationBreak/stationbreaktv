// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Writes the `filters` object of [RulesWriter], in the field order of docs/rules-format.md, "Filters". */
internal object FiltersWriter {
    fun write(filters: Filters): JsonObject =
        RulesWriter.obj(filters.extras) {
            filters.genres?.let { put("genres", names(it)) }
            filters.notGenres?.let { put("notGenres", names(it)) }
            filters.tags?.let { put("tags", names(it)) }
            filters.studios?.let { put("studios", names(it)) }
            val from = filters.yearFrom
            val to = filters.yearTo
            if (from != null && to != null) {
                put(
                    "years",
                    RulesWriter.obj(filters.yearsExtras) {
                        put("from", JsonPrimitive(from))
                        put("to", JsonPrimitive(to))
                    },
                )
            }
            if (filters.hasAnyRuntime) put("runtime", runtime(filters))
            filters.kind?.let { put("kind", JsonPrimitive(it.wireName)) }
        }

    private fun names(names: List<String>): JsonArray = JsonArray(names.map { JsonPrimitive(it) })

    private fun runtime(filters: Filters): JsonObject =
        RulesWriter.obj(filters.runtimeExtras) {
            filters.minRuntimeMinutes?.let { put("minMinutes", JsonPrimitive(it)) }
            filters.maxRuntimeMinutes?.let { put("maxMinutes", JsonPrimitive(it)) }
            window(filters.episodeMinRuntimeMinutes, filters.episodeMaxRuntimeMinutes, filters.episodeRuntimeExtras)
                ?.let { put("episode", it) }
            window(filters.movieMinRuntimeMinutes, filters.movieMaxRuntimeMinutes, filters.movieRuntimeExtras)
                ?.let { put("movie", it) }
        }

    private fun window(
        min: Int?,
        max: Int?,
        extras: JsonObject,
    ): JsonObject? =
        if (min == null && max == null) {
            null
        } else {
            RulesWriter.obj(extras) {
                min?.let { put("minMinutes", JsonPrimitive(it)) }
                max?.let { put("maxMinutes", JsonPrimitive(it)) }
            }
        }
}
