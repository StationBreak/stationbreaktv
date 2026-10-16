// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Small readers for the fields of a rules file. A wrong shape throws InvalidRules, which RulesReader turns into
// RulesResult.Invalid. The reasons name the field and never hold file content.

private val WHOLE_NUMBER = Regex("^-?(0|[1-9][0-9]*)$")

internal class InvalidRules(
    val reason: String,
) : Exception(reason)

internal fun invalid(reason: String): Nothing = throw InvalidRules(reason)

/** The first kind in a file that this version does not know. The file is then unsupported, not invalid. */
internal class UnsupportedKinds {
    var reason: String? = null
        private set

    fun mark(
        what: String,
        value: String,
    ) {
        if (reason == null) reason = "$what '${shown(value)}' is not known to this version"
    }

    /** A kind name from the file is shown only when it is short and plain, so the reason holds no file content. */
    private fun shown(value: String): String {
        val plainName = value.length <= MAX_SHOWN && value.all(::plain)
        return if (plainName) value else "(odd name)"
    }

    private fun plain(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'

    private companion object {
        const val MAX_SHOWN = 32
    }
}

internal fun required(
    obj: JsonObject,
    key: String,
    path: String = "",
): JsonElement = obj[key] ?: invalid("${if (path.isEmpty()) key else "$path.$key"} is missing")

internal fun string(
    element: JsonElement,
    path: String,
): String = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$path must be a string")

internal fun boolean(
    element: JsonElement,
    path: String,
): Boolean {
    val primitive = element as? JsonPrimitive
    val value = if (primitive != null && !primitive.isString) primitive.content.toBooleanStrictOrNull() else null
    return value ?: invalid("$path must be true or false")
}

internal fun int(
    element: JsonElement,
    path: String,
): Int {
    val primitive = element as? JsonPrimitive
    val content = if (primitive != null && !primitive.isString) primitive.content else null
    val strict = content != null && content != "-0" && WHOLE_NUMBER.matches(content)
    return (if (strict) content.toIntOrNull() else null) ?: invalid("$path must be a whole number")
}

internal fun ranged(
    element: JsonElement,
    path: String,
    min: Int,
    max: Int,
): Int {
    val value = int(element, path)
    if (value !in min..max) invalid("$path must be from $min to $max")
    return value
}

/** Text of 1 to [max] Unicode code points. */
internal fun name(
    element: JsonElement,
    path: String,
    max: Int,
): String {
    val text = string(element, path)
    val length = RulesLimits.codePoints(text)
    if (length < 1 || length > max) invalid("$path must be 1 to $max characters")
    return text
}

/** The fields of [obj] that are not in [known], in their order. */
internal fun extras(
    obj: JsonObject,
    known: Set<String>,
): JsonObject = JsonObject(obj.filterKeys { it !in known })
