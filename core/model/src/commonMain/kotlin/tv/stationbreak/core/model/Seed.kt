// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.jvm.JvmInline

/**
 * A channel's random seed: an unsigned 64-bit number that travels as a canonical decimal string (docs/rules-format.md,
 * "Seed"). Canonical means digits only, no sign, no leading zeros except `"0"`, at most 18446744073709551615. Because
 * [parse] accepts only that form, `parse(text).format() == text` always holds: a seed is written back as it was read.
 */
@JvmInline
value class Seed(
    val value: ULong,
) {
    /** The canonical decimal string. */
    fun format(): String = value.toString()

    override fun toString(): String = format()

    companion object {
        private const val MESSAGE = "A seed is a whole number from 0 to 18446744073709551615 with no leading zeros"
        private const val MAX_DIGITS = 20

        /** Parses the canonical decimal form or throws [IllegalArgumentException]. */
        fun parse(text: String): Seed = parseOrNull(text) ?: throw IllegalArgumentException(MESSAGE)

        /** Like [parse], but returns null for anything that is not the canonical form. */
        fun parseOrNull(text: String): Seed? {
            val shapeOk =
                text.isNotEmpty() && text.length <= MAX_DIGITS && text.all { it in '0'..'9' } &&
                    (text.length == 1 || text.first() != '0')
            // toULongOrNull rejects 20-digit values above the maximum, so the range check needs no big numbers.
            return if (shapeOk) text.toULongOrNull()?.let { Seed(it) } else null
        }
    }
}
