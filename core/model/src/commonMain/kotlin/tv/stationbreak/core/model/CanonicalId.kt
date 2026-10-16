// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.jvm.JvmInline

/**
 * An item ID in the one form the rules format uses: exactly 32 lower-case hexadecimal characters, no hyphens and no
 * braces (docs/rules-format.md, "Item IDs"). Jellyfin returns hyphenated or upper-case forms from some endpoints, so
 * [parse] normalises those; [parseCanonicalOrNull] is the strict check a rules-file reader uses, which repairs nothing.
 * [ItemId] is a different, looser type and is not changed.
 */
@JvmInline
value class CanonicalId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private const val LENGTH = 32
        private const val MESSAGE = "An ID is 32 hexadecimal characters"

        /** Normalises [raw] (hyphens and a pair of braces removed, lower case) or throws [IllegalArgumentException]. */
        fun parse(raw: String): CanonicalId = parseOrNull(raw) ?: throw IllegalArgumentException(MESSAGE)

        /** Like [parse], but returns null for anything that is not an ID. */
        fun parseOrNull(raw: String): CanonicalId? {
            val braced = raw.length >= 2 && raw.first() == '{' && raw.last() == '}'
            val unwrapped = if (braced) raw.substring(1, raw.length - 1) else raw
            val digits = unwrapped.replace("-", "").lowercase()
            return if (isCanonical(digits)) CanonicalId(digits) else null
        }

        /** Accepts only text that is already canonical: 32 lower-case hex characters. Nothing is repaired. */
        fun parseCanonicalOrNull(raw: String): CanonicalId? = if (isCanonical(raw)) CanonicalId(raw) else null

        private fun isCanonical(text: String): Boolean = text.length == LENGTH && text.all(::isLowerHex)

        private fun isLowerHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
    }
}
