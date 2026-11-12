// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes the published fixtures in one stable form: members in the order they were added (never sorted by a map), two
 * spaces of indent, a closing newline. An object or array of nothing but scalars, and an object that holds only such
 * values, sits on one line, so a block, an item or a channel's list of blocks stays one line each and a diff names it.
 */
internal object FixtureJson {
    private const val INDENT = "  "

    fun write(root: JsonObject): String = StringBuilder().also { render(root, it, 0) }.append('\n').toString()

    private fun isScalar(element: JsonElement) = element is JsonPrimitive || element is JsonNull

    private fun isFlat(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.values.all { isScalar(it) || (it is JsonArray && it.all(::isScalar)) }
            is JsonArray -> element.all(::isScalar)
            else -> true
        }

    private fun render(
        element: JsonElement,
        out: StringBuilder,
        depth: Int,
    ) {
        when {
            isScalar(element) -> {
                out.append(element.toString())
            }

            element is JsonObject && element.isEmpty() -> {
                out.append("{}")
            }

            element is JsonArray && element.isEmpty() -> {
                out.append("[]")
            }

            isFlat(element) -> {
                inline(element, out)
            }

            element is JsonObject -> {
                block(out, depth, "{}", element.entries.toList()) { (key, value) ->
                    out.append(JsonPrimitive(key)).append(": ")
                    render(value, out, depth + 1)
                }
            }

            element is JsonArray -> {
                block(out, depth, "[]", element.toList()) { render(it, out, depth + 1) }
            }
        }
    }

    private fun inline(
        element: JsonElement,
        out: StringBuilder,
    ) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(", ")
                    out.append(JsonPrimitive(key)).append(": ")
                    inline(value, out)
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) out.append(", ")
                    inline(value, out)
                }
                out.append(']')
            }

            else -> {
                out.append(element.toString())
            }
        }
    }

    private fun <T> block(
        out: StringBuilder,
        depth: Int,
        brackets: String,
        entries: List<T>,
        each: (T) -> Unit,
    ) {
        out.append(brackets.first()).append('\n')
        entries.forEachIndexed { i, entry ->
            out.append(INDENT.repeat(depth + 1))
            each(entry)
            out.append(if (i < entries.lastIndex) ",\n" else "\n")
        }
        out.append(INDENT.repeat(depth)).append(brackets.last())
    }
}

/** A JSON object whose members come in the order given. */
internal fun jsonOf(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))

internal fun json(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

internal fun json(value: Boolean): JsonElement = JsonPrimitive(value)

internal fun json(value: Int): JsonElement = JsonPrimitive(value)

/** A whole number of milliseconds as decimal text, as the published vectors write one. */
internal fun jsonMs(value: Long): JsonElement = JsonPrimitive(value.toString())

internal fun jsonArray(values: Iterable<JsonElement>): JsonArray = JsonArray(values.toList())

internal fun jsonStrings(values: Iterable<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
