// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.jvm.JvmInline

/**
 * How much of a series a day keeps (docs/rules-format.md, "The pool of an epoch", the trim window): a map from a
 * series ID to a cap in whole milliseconds, 1 or more. The scheduler turns the cap into a count of episodes from the
 * pool of the day and keeps a window of that many, so the count can change as the pool does. A side input to the day
 * build, never in the rules file; a series that is not in the map is kept whole.
 */
@JvmInline
value class TitleKeeps private constructor(
    private val byTitle: Map<CanonicalId, Long>,
) {
    /** The cap of [title] in milliseconds, or null when the title is kept whole. */
    operator fun get(title: CanonicalId): Long? = byTitle[title]

    val isEmpty: Boolean get() = byTitle.isEmpty()

    /** The (title, cap) pairs ascending by title ID bytes, whatever order the map was built in. */
    val entries: List<Pair<CanonicalId, Long>>
        get() = byTitle.entries.sortedBy { it.key.value }.map { it.key to it.value }

    companion object {
        /** Every series is kept whole. */
        val NONE: TitleKeeps = TitleKeeps(emptyMap())

        /** Throws [IllegalArgumentException] naming the title when a cap is below 1. */
        fun of(map: Map<CanonicalId, Long>): TitleKeeps {
            for ((title, cap) in map) {
                require(cap >= 1L) { "The cap of ${title.value} is 1 ms or more, got $cap" }
            }
            return TitleKeeps(HashMap(map))
        }
    }
}
