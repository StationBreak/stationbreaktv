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
    private val WHOLE_NUMBER = Regex("^-?(0|[1-9][0-9]*)$")

    /**
     * Reads a file given as bytes: the size limit is checked before anything is decoded, and the bytes must be valid
     * UTF-8. Callers should hand over at most [RulesLimits.MAX_BYTES] plus one byte (read that much and no more), so an
     * oversized or endless source is never held in memory.
     */
    fun read(bytes: ByteArray): RulesResult {
        val text =
            if (bytes.size > RulesLimits.MAX_BYTES) {
                null
            } else {
                try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    null
                }
            }
        return when {
            bytes.size > RulesLimits.MAX_BYTES -> RulesResult.Invalid(RulesLimits.TOO_BIG)
            text == null -> RulesResult.Invalid("The file is not valid UTF-8")
            else -> read(text)
        }
    }

    fun read(text: String): RulesResult {
        val problem = RulesLimits.check(text) ?: JsonProfile.problem(text)
        return if (problem != null) RulesResult.Invalid(problem) else readJson(text)
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
        } catch (e: IllegalArgumentException) {
            // The types check themselves too. A file whose unknown kinds were skipped can fail that check (no sources
            // left): it is then unsupported, not invalid.
            val unsupported = decoder.unsupported.reason
            if (unsupported != null) RulesResult.Unsupported(unsupported) else RulesResult.Invalid(e.message.orEmpty())
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

    private fun bad(text: String): Boolean = RulesLimits.hasUnpairedSurrogate(text)

    private fun hasUnpairedSurrogate(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.any { (key, value) -> bad(key) || hasUnpairedSurrogate(value) }
            is JsonArray -> element.any { hasUnpairedSurrogate(it) }
            is JsonPrimitive -> element.isString && RulesLimits.hasUnpairedSurrogate(element.content)
        }
}
