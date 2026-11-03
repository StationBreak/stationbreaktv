// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings

/** A generated library for the version 2 orders: shows, films, and films in collections. */
internal class V2Fixture(
    val items: List<ScheduleItem>,
    val films: FilmCollections,
)

private const val EPISODES_PER_SEASON = 12
private const val DATED_PERCENT = 80
private const val IN_COLLECTION_PERCENT = 75
private const val MIN_FILM_MINUTES = 80
private const val FILM_MINUTES_SPREAD = 70
private const val LONG_AGO_MS = -1_000_000_000_000L
private val EPISODE_MINUTES = listOf(22, 25, 42, 45, 60)

private fun two(n: Int) = n.toString().padStart(2, '0')

private fun SeededGen.date(year: Int): String {
    val month = two(1 + nextInt(12))
    val day = two(1 + nextInt(28))
    return "${year.toString().padStart(4, '0')}-$month-$day"
}

internal fun SeededGen.v2Fixture(
    series: Int,
    episodes: Int,
    films: Int,
    collections: Int,
    episodeMinutes: List<Int> = EPISODE_MINUTES,
): V2Fixture {
    val items = ArrayList<ScheduleItem>()
    for (show in List(series) { CanonicalId.parse(hexId()) }) {
        val first = 1960 + nextInt(60)
        val runtimeMs = pick(episodeMinutes) * MS_MINUTE
        for (e in 0 until episodes) {
            items +=
                ScheduleItem(
                    id = CanonicalId.parse(hexId()),
                    kind = ScheduleItemKind.Episode,
                    runtimeMs = runtimeMs,
                    createdAtMs = LONG_AGO_MS,
                    deletedAtMs = null,
                    premiereDate = if (chance(DATED_PERCENT)) date(first + e / 20) else null,
                    productionYear = null,
                    seriesId = show,
                    seasonNumber = 1 + e / EPISODES_PER_SEASON,
                    episodeNumber = 1 + e % EPISODES_PER_SEASON,
                )
        }
    }
    val collectionIds = List(collections) { CanonicalId.parse(hexId()) }
    val ofFilm = HashMap<CanonicalId, CanonicalId>()
    repeat(films) {
        val id = CanonicalId.parse(hexId())
        items +=
            ScheduleItem(
                id = id,
                kind = ScheduleItemKind.Movie,
                runtimeMs = (MIN_FILM_MINUTES + nextInt(FILM_MINUTES_SPREAD)) * MS_MINUTE,
                createdAtMs = LONG_AGO_MS,
                deletedAtMs = null,
                premiereDate = if (chance(DATED_PERCENT)) date(1950 + nextInt(75)) else null,
                productionYear = if (chance(90)) 1950 + nextInt(75) else null,
                seriesId = null,
                seasonNumber = null,
                episodeNumber = null,
            )
        if (collections > 0 && chance(IN_COLLECTION_PERCENT)) ofFilm[id] = collectionIds[nextInt(collections)]
    }
    return V2Fixture(items, FilmCollections.of(ofFilm))
}

/** The three version 2 orders as scheduler settings (1-block runs, a 24-hour day starting at 06:00). */
internal enum class V2Order {
    Rotation,
    InOrder,
    CappedShuffle,
    ;

    fun settings(
        seed: ULong,
        aligned: Boolean,
        blockLength: Int = 1,
    ): SchedulerSettings =
        testSettings(
            orderingMode =
                when (this) {
                    Rotation -> OrderingMode.Rotation
                    InOrder -> OrderingMode.InOrder
                    CappedShuffle -> OrderingMode.Shuffle
                },
            seed = Seed(seed),
            blockLength = blockLength,
            alignToGrid = aligned,
            epochHours = 24,
            startMinuteOfDay = 360,
            caps = if (this == InOrder) null else Caps(3, 1),
        )
}
