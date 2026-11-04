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
 * The runtime windows of `filters.runtime` in milliseconds: an item airs only inside the general window ([minMs],
 * [maxMs]) and inside the window of its own kind ([episodeMinMs], [episodeMaxMs] for episodes, [movieMinMs],
 * [movieMaxMs] for films), when there is one. At least one bound is set somewhere, each is at least 1, and a minimum is
 * not above its maximum.
 */
data class RuntimeFilter(
    val minMs: Long?,
    val maxMs: Long?,
    val episodeMinMs: Long? = null,
    val episodeMaxMs: Long? = null,
    val movieMinMs: Long? = null,
    val movieMaxMs: Long? = null,
) {
    init {
        require(
            minMs != null || maxMs != null || episodeMinMs != null || episodeMaxMs != null ||
                movieMinMs != null || movieMaxMs != null,
        ) { "A runtime filter needs a bound" }
        requireWindow(minMs, maxMs)
        requireWindow(episodeMinMs, episodeMaxMs)
        requireWindow(movieMinMs, movieMaxMs)
    }

    private fun requireWindow(
        min: Long?,
        max: Long?,
    ) {
        require(min == null || min >= 1L) { "The minimum runtime is at least 1 ms" }
        require(max == null || max >= 1L) { "The maximum runtime is at least 1 ms" }
        require(min == null || max == null || min <= max) { "The minimum runtime is not above the maximum" }
    }

    /** True when [runtimeMs] is inside the general window and the window of [kind]; a missing bound is open. */
    fun admits(
        runtimeMs: Long,
        kind: ScheduleItemKind,
    ): Boolean {
        val ofKind =
            when (kind) {
                ScheduleItemKind.Episode -> within(runtimeMs, episodeMinMs, episodeMaxMs)
                ScheduleItemKind.Movie -> within(runtimeMs, movieMinMs, movieMaxMs)
            }
        return within(runtimeMs, minMs, maxMs) && ofKind
    }

    private fun within(
        runtimeMs: Long,
        min: Long?,
        max: Long?,
    ): Boolean = (min == null || runtimeMs >= min) && (max == null || runtimeMs <= max)
}
