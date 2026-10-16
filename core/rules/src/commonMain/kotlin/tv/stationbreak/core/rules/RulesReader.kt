// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a rules file (docs/rules-format.md). The file is untrusted: the reader never throws for bad input, it returns
 * [RulesResult.Invalid] with a plain reason that holds no file content.
 *
 * The order is fixed: size and nesting limits, JSON, then `formatVersion` on its own (a newer file is "update required"
 * even if the rest of it is unreadable), then the body. Unknown fields are kept; an unknown kind makes the file
 * unsupported; a file that is malformed anywhere is invalid, whatever else it holds.
 */
object RulesReader {
    private const val BAD_TEXT = "The file has a character that is not valid text"
    private const val BELOW_ONE = "formatVersion must be 1 or more"
    private val WHOLE_NUMBER = Regex("^-?[0-9]+$")

    fun read(text: String): RulesResult {
        val tooBig = RulesLimits.check(text)
        return if (tooBig != null) RulesResult.Invalid(tooBig) else readJson(text)
    }

    private fun readJson(text: String): RulesResult {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        return when (root) {
            null -> RulesResult.Invalid("The file is not valid JSON")
            is JsonObject -> readObject(root)
            else -> RulesResult.Invalid("The file is not a JSON object")
        }
    }

    private fun readObject(root: JsonObject): RulesResult =
        when (val gate = versionGate(root)) {
            is VersionGate.Invalid -> RulesResult.Invalid(gate.reason)
            is VersionGate.Newer -> RulesResult.UpdateRequired(gate.version)
            VersionGate.Current -> if (hasUnpairedSurrogate(root)) RulesResult.Invalid(BAD_TEXT) else decode(root)
        }

    private fun decode(root: JsonObject): RulesResult {
        val decoder = RulesDecoder()
        return try {
            val rules = decoder.rules(root)
            val unsupported = decoder.unsupported.reason
            when {
                unsupported != null -> {
                    RulesResult.Unsupported(unsupported)
                }

                rules.fillerMode != FillerMode.None -> {
                    RulesResult.Unsupported("filler mode '${rules.fillerMode.wireName}' is not scheduled here", rules)
                }

                else -> {
                    RulesResult.Ok(rules)
                }
            }
        } catch (e: InvalidRules) {
            RulesResult.Invalid(e.reason)
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

    private fun versionGate(root: JsonObject): VersionGate {
        val primitive = root["formatVersion"] as? JsonPrimitive
        val digits = primitive?.takeIf { !it.isString && WHOLE_NUMBER.matches(it.content) }?.content
        val value = digits?.toLongOrNull()
        return when {
            digits == null -> {
                VersionGate.Invalid("formatVersion must be a whole number of 1 or more")
            }

            // Too big for a Long: positive is certainly newer than this reader, negative is below 1.
            value == null -> {
                if (digits.startsWith("-")) VersionGate.Invalid(BELOW_ONE) else VersionGate.Newer(Long.MAX_VALUE)
            }

            value < 1 -> {
                VersionGate.Invalid(BELOW_ONE)
            }

            value > RulesLimits.SUPPORTED_FORMAT_VERSION -> {
                VersionGate.Newer(value)
            }

            else -> {
                VersionGate.Current
            }
        }
    }

    private fun hasUnpairedSurrogate(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.any { (key, value) -> unpaired(key) || hasUnpairedSurrogate(value) }
            is JsonArray -> element.any { hasUnpairedSurrogate(it) }
            is JsonPrimitive -> element.isString && unpaired(element.content)
        }

    private fun unpaired(text: String): Boolean {
        var i = 0
        var found = false
        while (i < text.length && !found) {
            val c = text[i]
            val paired = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            found = c.isSurrogate() && !paired
            i += if (paired) 2 else 1
        }
        return found
    }
}
