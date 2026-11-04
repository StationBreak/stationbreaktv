// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.jvm.JvmInline

/**
 * How often a title comes round in the `curated` order (docs/rules-format.md, "curated"): a side input to the day
 * build, never in the rules file. A weight is 1 to 3; a title that is not in the map has weight 1, and so does every
 * title when a rules file is read on its own.
 */
@JvmInline
value class TitleWeights private constructor(
    private val byTitle: Map<CanonicalId, Int>,
) {
    /** The weight of [title] (a series ID, or the ID of a film or a series-less episode): 1 when it is absent. */
    operator fun get(title: CanonicalId): Int = byTitle[title] ?: 1

    val isEmpty: Boolean get() = byTitle.isEmpty()

    /** The (title, weight) pairs ascending by title ID bytes, whatever order the map was built in. */
    val entries: List<Pair<CanonicalId, Int>>
        get() = byTitle.entries.sortedBy { it.key.value }.map { it.key to it.value }

    companion object {
        const val MIN_WEIGHT = 1
        const val MAX_WEIGHT = 3

        /** Every title has weight 1. */
        val NONE: TitleWeights = TitleWeights(emptyMap())

        /** Throws [IllegalArgumentException] naming the title when a weight is outside 1 to 3. */
        fun of(map: Map<CanonicalId, Int>): TitleWeights {
            for ((title, weight) in map) {
                require(weight in MIN_WEIGHT..MAX_WEIGHT) { "The weight of ${title.value} is 1 to 3, got $weight" }
            }
            return TitleWeights(HashMap(map))
        }
    }
}
