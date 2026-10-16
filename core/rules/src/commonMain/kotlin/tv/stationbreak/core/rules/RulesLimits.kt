// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/** The limits a reader applies to a file from outside (docs/rules-format.md, "Limits"). */
object RulesLimits {
    /** The version this reader schedules. */
    const val SUPPORTED_FORMAT_VERSION: Long = 1

    const val MAX_BYTES: Int = 256 * 1024
    const val MAX_DEPTH: Int = 16
    const val MAX_LIST_ENTRIES: Int = 256
    const val MAX_NAME_CODE_POINTS: Int = 100
    const val MAX_TEXT_CODE_POINTS: Int = 256

    /** Null when [text] is inside the size and nesting limits, otherwise a plain reason. Looks at nothing else. */
    internal fun check(text: String): String? =
        when {
            text.length > MAX_BYTES || text.encodeToByteArray().size > MAX_BYTES -> "The file is larger than 256 KiB"
            nestingDepth(text) > MAX_DEPTH -> "The file is nested more than $MAX_DEPTH levels deep"
            else -> null
        }

    /** The deepest nesting of objects and lists in [text], counted on the raw text so no parser sees a deep file. */
    private fun nestingDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> deepest = maxOf(deepest, ++depth)
                    '}', ']' -> if (depth > 0) depth--
                }
            }
            if (deepest > MAX_DEPTH) return deepest
        }
        return deepest
    }

    /** Unicode code points in [text]. A pair of surrogates counts once. */
    internal fun codePoints(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }
}
