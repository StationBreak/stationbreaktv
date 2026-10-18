// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.testing

import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.SeriesInfo

/**
 * The series and collections that go with a [SyntheticLibrary], for the size tests of the rule snapshot (spec 10,
 * Unit 3): [seriesCount] series, whose IDs are those the library's episodes point to (the first [seriesCount] of them),
 * and [collectionCount] collections of about 50 members, the first of 2,000. Everything is made on demand, the same
 * every time. A [round] stands for one sync: it changes the rating of every series, so each round supersedes the last
 * one, and swaps a few members of every collection. Names are placeholders.
 */
class SyntheticRuleInputs(
    val library: SyntheticLibrary,
    val seriesCount: Int = 500,
    val collectionCount: Int = 100,
) {
    init {
        require(seriesCount >= 0 && collectionCount >= 0) { "counts can't be negative" }
    }

    /** The ID of series [index], as the library's episodes name it. */
    fun seriesId(index: Int): ItemId = ItemId(hexId(index, SERIES_PREFIX))

    /** The ID of collection [index]. */
    fun collectionId(index: Int): ItemId = ItemId(hexId(index, COLLECTION_PREFIX))

    /** Every series as the server describes it in [round]: the rating, the genres and the tags change with it. */
    fun series(round: Int): List<SeriesInfo> =
        List(seriesCount) { i ->
            SeriesInfo(
                id = seriesId(i),
                name = "Series $i",
                officialRating = RATINGS[(i + round) % RATINGS.size],
                genres = listOf(GENRES[i % GENRES.size], GENRES[(i + round + 1) % GENRES.size]).distinct().sorted(),
                tags = if (i % 3 == 0) listOf(TAGS[(i + round) % TAGS.size]) else emptyList(),
            )
        }

    /** The members of collection [index] in [round]: [BASE] more than its size, less the few that round swaps out. */
    fun members(
        index: Int,
        round: Int,
    ): List<CollectionMember> {
        val size = if (index == 0) BIG else NORMAL
        val swapped = setOf(round % size, (round + SWAP_GAP) % size)
        return (0 until size + SPARE).filter { it !in swapped }.map { member(index, it) }
    }

    private fun member(
        collection: Int,
        position: Int,
    ): CollectionMember {
        val number = collection * STRIDE + position
        return when {
            position % SERIES_EVERY == 0 -> {
                CollectionMember(seriesId((number / SERIES_EVERY) % maxOf(seriesCount, 1)), CollectionMemberKind.Series)
            }

            position % SEASON_EVERY == 0 -> {
                CollectionMember(ItemId(hexId(number, SEASON_PREFIX)), CollectionMemberKind.Season)
            }

            else -> {
                val item = (number * ITEM_MIX) % maxOf(library.size, 1)
                val kind = if (position % 2 == 0) CollectionMemberKind.Movie else CollectionMemberKind.Episode
                CollectionMember(ItemId(hexId(item, ITEM_PREFIX)), kind)
            }
        }
    }

    private fun hexId(
        number: Int,
        prefix: Char,
    ): String = prefix + number.toString(HEX).padStart(ID_LENGTH - 1, '0')

    private companion object {
        const val HEX = 16
        const val ID_LENGTH = 32
        const val ITEM_PREFIX = '0'
        const val SERIES_PREFIX = '5'
        const val SEASON_PREFIX = '6'
        const val COLLECTION_PREFIX = 'c'
        const val BIG = 2_000
        const val NORMAL = 50
        const val SPARE = 4
        const val SWAP_GAP = 7
        const val STRIDE = 10_007
        const val ITEM_MIX = 13
        const val SERIES_EVERY = 10
        const val SEASON_EVERY = 25
        val RATINGS = listOf("TV-G", "TV-PG", "TV-14", "TV-MA", "NR")
        val GENRES = listOf("Action", "Comedy", "Drama", "Documentary", "Horror", "Music", "Sci-Fi")
        val TAGS = listOf("weekend", "late-night", "classic")
    }
}
