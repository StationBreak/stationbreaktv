// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed

/**
 * Reads a rules file (docs/rules-format.md). The file is untrusted: the reader never throws for bad input, it returns
 * [RulesResult.Invalid] with a plain reason that holds no file content.
 *
 * The order is fixed: size and nesting limits, JSON, then `formatVersion` on its own (a newer file is "update required"
 * even if the rest of it is unreadable), then the body. Unknown fields are kept; an unknown kind makes the file
 * unsupported; a file that is malformed anywhere is invalid, whatever else it holds.
 */
object RulesReader {
    fun read(text: String): RulesResult {
        RulesLimits.check(text)?.let { return RulesResult.Invalid(it) }
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                return RulesResult.Invalid("The file is not valid JSON")
            }
        if (root !is JsonObject) return RulesResult.Invalid("The file is not a JSON object")
        when (val gate = versionGate(root)) {
            is VersionGate.Invalid -> return RulesResult.Invalid(gate.reason)
            is VersionGate.Newer -> return RulesResult.UpdateRequired(gate.version)
            VersionGate.Current -> Unit
        }
        if (hasUnpairedSurrogate(root)) return RulesResult.Invalid("The file has a character that is not valid text")
        return try {
            decode(root)
        } catch (e: InvalidRules) {
            RulesResult.Invalid(e.reason)
        }
    }

    private fun decode(root: JsonObject): RulesResult {
        val decoder = Decoder()
        val rules = decoder.rules(root)
        val unsupported = decoder.unsupported
        return when {
            unsupported != null -> RulesResult.Unsupported(unsupported)
            rules.fillerMode != FillerMode.None ->
                RulesResult.Unsupported("filler mode '${rules.fillerMode.wireName}' is not scheduled by this version", rules)
            else -> RulesResult.Ok(rules)
        }
    }

    private sealed interface VersionGate {
        data object Current : VersionGate

        data class Newer(
            val version: Long,
        ) : VersionGate

        data class Invalid(
            val reason: String,
        ) : VersionGate
    }

    private val WHOLE_NUMBER = Regex("^-?[0-9]+$")

    private fun versionGate(root: JsonObject): VersionGate {
        val primitive = root["formatVersion"] as? JsonPrimitive
        val digits = primitive?.takeIf { !it.isString && WHOLE_NUMBER.matches(it.content) }?.content
        val value = digits?.toLongOrNull()
        return when {
            digits == null -> VersionGate.Invalid("formatVersion must be a whole number of 1 or more")
            // Too big for a Long: positive is certainly newer than this reader, negative is below 1.
            value == null -> if (digits.startsWith("-")) VersionGate.Invalid(BELOW_ONE) else VersionGate.Newer(Long.MAX_VALUE)
            value < 1 -> VersionGate.Invalid(BELOW_ONE)
            value > RulesLimits.SUPPORTED_FORMAT_VERSION -> VersionGate.Newer(value)
            else -> VersionGate.Current
        }
    }

    private const val BELOW_ONE = "formatVersion must be 1 or more"

    private fun hasUnpairedSurrogate(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.any { (key, value) -> unpaired(key) || hasUnpairedSurrogate(value) }
            is JsonArray -> element.any { hasUnpairedSurrogate(it) }
            is JsonPrimitive -> element.isString && unpaired(element.content)
        }

    private fun unpaired(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i += 2
            } else if (c.isSurrogate()) {
                return true
            } else {
                i++
            }
        }
        return false
    }
}

private class InvalidRules(
    val reason: String,
) : Exception(reason)

private fun invalid(reason: String): Nothing = throw InvalidRules(reason)

private val RATINGS =
    setOf(
        "TV-Y", "TV-G", "G", "Approved", "TV-Y7", "TV-Y7-FV", "TV-PG", "PG", "PG-13", "TV-14", "R", "TV-MA", "NC-17",
        "TV-X", "TV-AO",
    )
private val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
private val START_TIME = Regex("^([01][0-9]|2[0-3]):(00|30)$")
private val SOURCE_KINDS = setOf("library", "genre", "collection", "tag", "yearRange", "series")
private val FILLER_SOURCE_KINDS = setOf("library", "collection", "tag")

private const val MAX_SHOWN = 32
private const val MAX_BLOCK_LENGTH = 100
private const val MAX_EVERY = 1000
private const val MIN_YEAR = 1
private const val MAX_YEAR = 9999
private const val MIN_OFFSET = -720
private const val MAX_OFFSET = 840
private const val OFFSET_STEP = 15
private const val MAX_NAME = RulesLimits.MAX_NAME_CODE_POINTS
private const val MAX_TEXT = RulesLimits.MAX_TEXT_CODE_POINTS
private const val MAX_LIST = RulesLimits.MAX_LIST_ENTRIES

private class Decoder {
    /** The first kind this version does not know. The file is then unsupported. */
    var unsupported: String? = null
        private set

    private fun markUnsupported(
        what: String,
        value: String,
    ) {
        if (unsupported == null) unsupported = "$what '${shown(value)}' is not known to this version"
    }

    /** A kind name from the file is shown only when it is short and plain, so the reason holds no file content. */
    private fun shown(value: String): String =
        if (value.length <= MAX_SHOWN && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) value else "(unrecognised)"

    fun rules(root: JsonObject): ChannelRules {
        val known = setOf("formatVersion", "name", "seed", "sources", "exclusions", "maxRating", "ordering", "timing", "filler")
        return ChannelRules(
            formatVersion = int(root.required("formatVersion"), "formatVersion"),
            name = root["name"]?.let { name(it, "name", MAX_NAME) },
            seed = seed(root),
            sources = sourceList(root.required("sources"), "sources", min = 1),
            exclusions = root["exclusions"]?.let { sourceList(it, "exclusions", min = 0) },
            maxRating = root["maxRating"]?.let { maxRating(it) },
            ordering = root["ordering"]?.let { ordering(it) },
            timing = timing(root.required("timing")),
            filler = root["filler"]?.let { filler(it) },
            extras = extras(root, known),
        )
    }

    private fun seed(root: JsonObject): Seed {
        val element = root.required("seed")
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        return text?.let { Seed.parseOrNull(it) }
            ?: invalid("seed must be a decimal string of a whole number from 0 to 18446744073709551615, with no leading zeros")
    }

    private fun maxRating(element: JsonElement): String {
        val text = string(element, "maxRating")
        if (text !in RATINGS) invalid("maxRating is not a rating on the ladder")
        return text
    }

    private fun sourceList(
        element: JsonElement,
        path: String,
        min: Int,
    ): List<Source> {
        val array = element as? JsonArray ?: invalid("$path must be a list")
        if (array.size < min) invalid("$path must have at least $min entry")
        if (array.size > MAX_LIST) invalid("$path has more than $MAX_LIST entries")
        return array.mapIndexedNotNull { i, item -> source(item, "$path[$i]", filler = false) }
    }

    private fun source(
        element: JsonElement,
        path: String,
        filler: Boolean,
    ): Source? {
        val obj = element as? JsonObject ?: invalid("$path must be an object")
        val type = string(obj.required("type", path), "$path.type")
        if (type !in SOURCE_KINDS) {
            markUnsupported("source kind", type)
            return null
        }
        if (filler && type !in FILLER_SOURCE_KINDS) invalid("$path.type must be a library, a collection or a tag")
        return when (type) {
            "library" -> Source.Library(id(obj, path), extras(obj, setOf("type", "id")))
            "collection" -> Source.Collection(id(obj, path), extras(obj, setOf("type", "id")))
            "series" -> Source.Series(id(obj, path), extras(obj, setOf("type", "id")))
            "genre" -> Source.Genre(sourceName(obj, path), extras(obj, setOf("type", "name")))
            "tag" -> Source.Tag(sourceName(obj, path), extras(obj, setOf("type", "name")))
            else -> yearRange(obj, path)
        }
    }

    private fun id(
        obj: JsonObject,
        path: String,
    ): CanonicalId =
        CanonicalId.parseCanonicalOrNull(string(obj.required("id", path), "$path.id"))
            ?: invalid("$path.id must be 32 lower-case hexadecimal characters")

    private fun sourceName(
        obj: JsonObject,
        path: String,
    ): String = name(obj.required("name", path), "$path.name", MAX_TEXT)

    private fun yearRange(
        obj: JsonObject,
        path: String,
    ): Source.YearRange {
        val from = ranged(obj.required("from", path), "$path.from", MIN_YEAR, MAX_YEAR)
        val to = ranged(obj.required("to", path), "$path.to", MIN_YEAR, MAX_YEAR)
        if (to < from) invalid("$path.to must not be lower than $path.from")
        return Source.YearRange(from, to, extras(obj, setOf("type", "from", "to")))
    }

    private fun ordering(element: JsonElement): Ordering? {
        val obj = element as? JsonObject ?: invalid("ordering must be an object")
        val text = string(obj.required("mode", "ordering"), "ordering.mode")
        val mode = OrderingMode.entries.firstOrNull { it.wireName == text }
        if (mode == null) {
            markUnsupported("ordering mode", text)
            return null
        }
        return Ordering(mode, extras(obj, setOf("mode")))
    }

    private fun timing(element: JsonElement): Timing {
        val obj = element as? JsonObject ?: invalid("timing must be an object")
        val epochHours = obj["epochHours"]?.let { int(it, "timing.epochHours") }
        if (epochHours != null && epochHours !in EPOCH_HOURS) invalid("timing.epochHours must divide 24 and be on the list")
        return Timing(
            blockLength = obj["blockLength"]?.let { ranged(it, "timing.blockLength", 1, MAX_BLOCK_LENGTH) },
            alignToGrid = obj["alignToGrid"]?.let { boolean(it, "timing.alignToGrid") },
            epochHours = epochHours,
            broadcastDay = broadcastDay(obj.required("broadcastDay", "timing")),
            extras = extras(obj, setOf("blockLength", "alignToGrid", "epochHours", "broadcastDay")),
        )
    }

    private fun broadcastDay(element: JsonElement): BroadcastDay {
        val obj = element as? JsonObject ?: invalid("timing.broadcastDay must be an object")
        val path = "timing.broadcastDay"
        val offset = ranged(obj.required("utcOffsetMinutes", path), "$path.utcOffsetMinutes", MIN_OFFSET, MAX_OFFSET)
        if (offset % OFFSET_STEP != 0) invalid("$path.utcOffsetMinutes must be a multiple of 15")
        val start = obj["start"]?.let { string(it, "$path.start") }
        if (start != null && !START_TIME.matches(start)) invalid("$path.start must be HH:MM on the hour or half hour")
        return BroadcastDay(offset, start, extras(obj, setOf("utcOffsetMinutes", "start")))
    }

    private fun filler(element: JsonElement): Filler? {
        val obj = element as? JsonObject ?: invalid("filler must be an object")
        val text = string(obj.required("mode", "filler"), "filler.mode")
        val mode = FillerMode.entries.firstOrNull { it.wireName == text }
        if (mode == null) {
            markUnsupported("filler mode", text)
            return null
        }
        val every = obj["every"]?.let { ranged(it, "filler.every", 1, MAX_EVERY) }
        val source = obj["source"]?.let { source(it, "filler.source", filler = true) }
        if (mode == FillerMode.BetweenEveryN && every == null) invalid("filler.every is required for betweenEveryN")
        if (mode != FillerMode.None && source == null && unsupported == null) invalid("filler.source is required for $text")
        return Filler(mode, every, source, extras(obj, setOf("mode", "every", "source")))
    }

    private fun extras(
        obj: JsonObject,
        known: Set<String>,
    ): JsonObject = JsonObject(obj.filterKeys { it !in known })

    private fun JsonObject.required(
        key: String,
        path: String = "",
    ): JsonElement = this[key] ?: invalid("${if (path.isEmpty()) key else "$path.$key"} is missing")

    private fun string(
        element: JsonElement,
        path: String,
    ): String = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$path must be a string")

    private fun boolean(
        element: JsonElement,
        path: String,
    ): Boolean {
        val primitive = element as? JsonPrimitive
        val value = if (primitive != null && !primitive.isString) primitive.content.toBooleanStrictOrNull() else null
        return value ?: invalid("$path must be true or false")
    }

    private fun int(
        element: JsonElement,
        path: String,
    ): Int {
        val primitive = element as? JsonPrimitive
        val value = if (primitive != null && !primitive.isString) primitive.content.toIntOrNull() else null
        return value ?: invalid("$path must be a whole number")
    }

    private fun ranged(
        element: JsonElement,
        path: String,
        min: Int,
        max: Int,
    ): Int {
        val value = int(element, path)
        if (value !in min..max) invalid("$path must be from $min to $max")
        return value
    }

    private fun name(
        element: JsonElement,
        path: String,
        max: Int,
    ): String {
        val text = string(element, path)
        val length = RulesLimits.codePoints(text)
        if (length < 1 || length > max) invalid("$path must be 1 to $max characters")
        return text
    }
}
