// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

/** Numbers as text by integer arithmetic only, so a report is the same on every machine and locale. */
internal object AuditFormat {
    private const val MS_PER_HOUR = 3_600_000L
    private const val HUNDRED = 100L
    private const val COUNT_WIDTH = 5
    private const val Q16_ONE = 65_536L

    /** [ms] in hundredths of an hour, rounded half up. */
    fun centiHours(ms: Long): Long = (ms * HUNDRED + MS_PER_HOUR / 2) / MS_PER_HOUR

    /** [ms] as hours to two places: `12.34`. */
    fun hours(ms: Long): String = fixed(centiHours(ms))

    /** [q16] points (16-bit fixed point) to two places, half away from zero: `-150.00`, `12.34`. */
    fun points(q16: Long): String {
        val centi = (Math.abs(q16) * HUNDRED + Q16_ONE / 2) / Q16_ONE
        return fixed(if (q16 < 0) -centi else centi)
    }

    /** [n] as text, padded to a column. */
    fun count(n: Int): String = n.toString().padStart(COUNT_WIDTH)

    /** The terms of a score that are not zero, as `name points` pairs. */
    fun terms(t: TermValues): String =
        TERM_NAMES.indices.filter { t.q16[it] != 0L }.joinToString(" ") { "${TERM_NAMES[it]} ${points(t.q16[it])}" }

    // A count of hundredths as text with two decimals.
    private fun fixed(centi: Long): String {
        val sign = if (centi < 0) "-" else ""
        val abs = Math.abs(centi)
        return "$sign${abs / HUNDRED}.${(abs % HUNDRED).toString().padStart(2, '0')}"
    }
}
