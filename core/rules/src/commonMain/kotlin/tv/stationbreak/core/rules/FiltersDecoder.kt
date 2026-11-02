// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val MAX_YEAR = RulesVocabulary.MAX_YEAR
private const val MAX_MINUTES = RulesVocabulary.MAX_RUNTIME_MINUTES

/** Reads the `filters` object of a version 2 file. An unknown `kind` is recorded in [unsupported]. */
internal class FiltersDecoder(
    private val unsupported: UnsupportedKinds,
) {
    fun filters(element: JsonElement): Filters? {
        val obj = element as? JsonObject ?: invalid("filters must be an object")
        val years = obj["years"]?.let { years(it) }
        val runtime = obj["runtime"]?.let { runtime(it) }
        val kind = obj["kind"]?.let { kind(it) }
        return if (obj["kind"] != null && kind == null) {
            null
        } else {
            Filters(
                genres = obj["genres"]?.let { genres(it) },
                yearFrom = years?.from,
                yearTo = years?.to,
                minRuntimeMinutes = runtime?.min,
                maxRuntimeMinutes = runtime?.max,
                kind = kind,
                extras = extras(obj, RulesVocabulary.FILTERS),
                yearsExtras = years?.extras ?: NO_EXTRAS,
                runtimeExtras = runtime?.extras ?: NO_EXTRAS,
            )
        }
    }

    private fun genres(element: JsonElement): List<String> {
        val array = element as? JsonArray ?: invalid("filters.genres must be a list")
        if (array.isEmpty()) invalid("filters.genres must have at least 1 entry")
        if (array.size > RulesLimits.MAX_LIST_ENTRIES) invalid("filters.genres has more than 256 entries")
        return array.mapIndexed { i, item -> name(item, "filters.genres[$i]", RulesLimits.MAX_TEXT_CODE_POINTS) }
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
    )

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
        val min = obj["minMinutes"]?.let { ranged(it, "filters.runtime.minMinutes", 1, MAX_MINUTES) }
        val max = obj["maxMinutes"]?.let { ranged(it, "filters.runtime.maxMinutes", 1, MAX_MINUTES) }
        if (min == null && max == null) invalid("filters.runtime needs minMinutes or maxMinutes")
        if (min != null && max != null && min > max) invalid("filters.runtime.maxMinutes must not be below minMinutes")
        return Runtime(min, max, extras(obj, RulesVocabulary.FILTER_RUNTIME))
    }
}
