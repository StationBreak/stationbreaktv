// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.CanonicalId

private val SOURCE_KINDS = setOf("library", "genre", "collection", "tag", "yearRange", "series")
private val FILLER_SOURCE_KINDS = setOf("library", "collection", "tag")
private const val MAX_LIST = RulesLimits.MAX_LIST_ENTRIES
private const val MIN_YEAR = 1
private const val MAX_YEAR = 9999

/** Reads sources, exclusions and filler sources. An unknown kind is recorded in [unsupported] and skipped. */
internal class SourceDecoder(
    private val unsupported: UnsupportedKinds,
) {
    fun list(
        element: JsonElement,
        path: String,
        min: Int,
    ): List<Source> {
        val array = element as? JsonArray ?: invalid("$path must be a list")
        if (array.size < min) invalid("$path must have at least $min entry")
        if (array.size > MAX_LIST) invalid("$path has more than $MAX_LIST entries")
        return array.mapIndexedNotNull { i, item -> source(item, "$path[$i]", filler = false) }
    }

    fun source(
        element: JsonElement,
        path: String,
        filler: Boolean,
    ): Source? {
        val obj = element as? JsonObject ?: invalid("$path must be an object")
        val type = string(required(obj, "type", path), "$path.type")
        val known = type in SOURCE_KINDS
        if (known && filler && type !in FILLER_SOURCE_KINDS) invalid("$path.type must be library, collection or tag")
        if (!known) unsupported.mark("source kind", type)
        return if (known) build(type, obj, path) else null
    }

    private fun build(
        type: String,
        obj: JsonObject,
        path: String,
    ): Source =
        when (type) {
            "library" -> Source.Library(id(obj, path), extras(obj, setOf("type", "id")))
            "collection" -> Source.Collection(id(obj, path), extras(obj, setOf("type", "id")))
            "series" -> Source.Series(id(obj, path), extras(obj, setOf("type", "id")))
            "genre" -> Source.Genre(sourceName(obj, path), extras(obj, setOf("type", "name")))
            "tag" -> Source.Tag(sourceName(obj, path), extras(obj, setOf("type", "name")))
            else -> yearRange(obj, path)
        }

    private fun id(
        obj: JsonObject,
        path: String,
    ): CanonicalId =
        CanonicalId.parseCanonicalOrNull(string(required(obj, "id", path), "$path.id"))
            ?: invalid("$path.id must be 32 lower-case hexadecimal characters")

    private fun sourceName(
        obj: JsonObject,
        path: String,
    ): String = name(required(obj, "name", path), "$path.name", RulesLimits.MAX_TEXT_CODE_POINTS)

    private fun yearRange(
        obj: JsonObject,
        path: String,
    ): Source.YearRange {
        val from = ranged(required(obj, "from", path), "$path.from", MIN_YEAR, MAX_YEAR)
        val to = ranged(required(obj, "to", path), "$path.to", MIN_YEAR, MAX_YEAR)
        if (to < from) invalid("$path.to must not be lower than $path.from")
        return Source.YearRange(from, to, extras(obj, setOf("type", "from", "to")))
    }
}
