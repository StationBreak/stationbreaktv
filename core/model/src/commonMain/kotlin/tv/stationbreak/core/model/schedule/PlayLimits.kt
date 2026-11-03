// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

/**
 * How many of one show's episodes, or one collection's films, may play in a row (docs/rules-format.md,
 * `ordering.caps`), as numbers. Both are 1 to 10. The defaults of the optional rules fields live only in `core:rules`.
 */
data class Caps(
    val series: Int,
    val film: Int,
) {
    init {
        require(series in MIN_CAP..MAX_CAP) { "A series cap is 1 to 10" }
        require(film in MIN_CAP..MAX_CAP) { "A film cap is 1 to 10" }
    }

    private companion object {
        const val MIN_CAP = 1
        const val MAX_CAP = 10
    }
}

/**
 * The runtime window of `filters.runtime` in milliseconds: an item airs only inside it. At least one bound is set, each
 * is at least 1, and [minMs] is not above [maxMs].
 */
data class RuntimeFilter(
    val minMs: Long?,
    val maxMs: Long?,
) {
    init {
        require(minMs != null || maxMs != null) { "A runtime filter needs a bound" }
        require(minMs == null || minMs >= 1L) { "The minimum runtime is at least 1 ms" }
        require(maxMs == null || maxMs >= 1L) { "The maximum runtime is at least 1 ms" }
        require(minMs == null || maxMs == null || minMs <= maxMs) { "The minimum runtime is not above the maximum" }
    }

    /** True when [runtimeMs] is inside the window; a missing bound is open. */
    fun admits(runtimeMs: Long): Boolean {
        val aboveMin = minMs == null || runtimeMs >= minMs
        val belowMax = maxMs == null || runtimeMs <= maxMs
        return aboveMin && belowMax
    }
}
