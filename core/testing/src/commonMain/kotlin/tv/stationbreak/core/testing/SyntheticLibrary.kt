// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.testing

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A generated library of [size] video items, the same every time for the same [size] and [seed] (F0.6). Nothing is
 * checked in: items are made on demand, so a 15,000-item library costs nothing until it is read, and [items] is a
 * sequence so a test can stream it. Names are placeholders ("Item 17"), never real titles.
 *
 * Items come in ascending ID order, which is the order the adapter asks the server for. About one in fifty has no
 * running time and one in fifty a zero one, so the scheduler's exclusion rule always has something to exclude.
 */
class SyntheticLibrary(
    val size: Int,
    val seed: Long = 0L,
    val libraryId: ItemId = ItemId("11111111111111111111111111111111"),
) {
    init {
        require(size >= 0) { "size can't be negative" }
    }

    /** Every item, in ascending ID order. */
    val items: Sequence<MediaItem> get() = (0 until size).asSequence().map(::item)

    /** The item at [index] (0 until [size]), the same whether read alone or as part of [items]. */
    fun item(index: Int): MediaItem {
        require(index in 0 until size) { "index $index is outside 0 until $size" }
        val random = Random(seed * SEED_MIX + index)
        val roll = random.nextInt(PERCENT)
        val kind =
            when {
                roll < MOVIES -> ItemKind.Movie
                roll < EPISODES -> ItemKind.Episode
                roll < VIDEOS -> ItemKind.Video
                else -> ItemKind.MusicVideo
            }
        val created = BASE_DATE + random.nextLong(CREATED_SPREAD_SECONDS).seconds
        val episode = kind == ItemKind.Episode
        return MediaItem(
            id = ItemId(hexId(index, ITEM_PREFIX)),
            name = "Item $index",
            kind = kind,
            runtime = runtime(random),
            parentId = libraryId,
            dateCreated = created,
            productionYear = YEAR_FIRST + random.nextInt(YEAR_COUNT),
            officialRating = RATINGS[random.nextInt(RATINGS.size)],
            genres = pick(random, GENRES, random.nextInt(MAX_GENRES + 1)),
            tags = pick(random, TAGS, random.nextInt(MAX_TAGS + 1)),
            seriesId = if (episode) ItemId(hexId(index / EPISODES_PER_SERIES, SERIES_PREFIX)) else null,
            seasonNumber = if (episode) 1 + (index / EPISODES_PER_SEASON) % SEASONS else null,
            episodeNumber = if (episode) 1 + index % EPISODES_PER_SEASON else null,
        )
    }

    /**
     * When the server last saved the item at [index]: never before it was created. The model has no such field,
     * because Jellyfin doesn't return it, but a fake server needs it to answer "changed since".
     */
    fun savedAt(index: Int): Instant {
        val created = checkNotNull(item(index).dateCreated)
        return created + Random(seed * SEED_MIX + index + SAVED_MIX).nextLong(SAVED_SPREAD_DAYS).days
    }

    /** Up to [limit] items from [startIndex] on, as a server page would give them. Empty past the end. */
    fun page(
        startIndex: Int,
        limit: Int,
    ): List<MediaItem> {
        require(startIndex >= 0 && limit >= 0) { "startIndex and limit can't be negative" }
        val end = minOf(size.toLong(), startIndex.toLong() + limit).toInt()
        return (startIndex until end).map(::item)
    }

    private fun runtime(random: Random): Duration? {
        val roll = random.nextInt(PERCENT)
        return when {
            roll < NO_RUNTIME -> null
            roll < ZERO_RUNTIME -> Duration.ZERO
            else -> random.nextInt(MAX_RUNTIME_MINUTES).minutes + random.nextInt(SECONDS_PER_MINUTE).seconds + 1.minutes
        }
    }

    private fun pick(
        random: Random,
        from: List<String>,
        count: Int,
    ): List<String> = from.shuffled(random).take(count).sorted()

    /** A 32-digit lower-case hex ID: the shape Jellyfin uses, and ordered the same as [number]. */
    private fun hexId(
        number: Int,
        prefix: Char,
    ): String = prefix + number.toString(HEX).padStart(ID_LENGTH - 1, '0')

    private companion object {
        const val HEX = 16
        const val ID_LENGTH = 32
        const val ITEM_PREFIX = '0'
        const val SERIES_PREFIX = '5'
        const val SEED_MIX = 1_000_003L
        const val PERCENT = 100
        const val MOVIES = 45
        const val EPISODES = 85
        const val VIDEOS = 95
        const val NO_RUNTIME = 2
        const val ZERO_RUNTIME = 4
        const val MAX_RUNTIME_MINUTES = 180
        const val SECONDS_PER_MINUTE = 60
        const val CREATED_SPREAD_SECONDS = 6L * 365 * 24 * 3600
        const val SAVED_SPREAD_DAYS = 30L
        const val SAVED_MIX = 7L
        const val YEAR_FIRST = 1950
        const val YEAR_COUNT = 76
        const val MAX_GENRES = 2
        const val MAX_TAGS = 1
        const val EPISODES_PER_SERIES = 20
        const val EPISODES_PER_SEASON = 10
        const val SEASONS = 2
        val BASE_DATE: Instant = Instant.parse("2020-01-01T00:00:00Z")
        val RATINGS = listOf("G", "PG", "PG-13", "R", "TV-G", "TV-14", "TV-MA")
        val GENRES = listOf("Action", "Comedy", "Drama", "Documentary", "Horror", "Music", "Sci-Fi")
        val TAGS = listOf("weekend", "late-night", "classic")
    }
}
