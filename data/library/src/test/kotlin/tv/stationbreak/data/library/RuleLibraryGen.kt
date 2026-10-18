// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ordering.splitmix64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A deterministic generator for the rule-snapshot property tests (spec 10, Unit 3), built on `splitmix64` as
 * `SeededGen` in `core:scheduler`'s tests is (copied, not depended on). Rerun a failing case with
 * `SeededGen(<seed from the failure message>)`.
 */
internal class SeededGen(
    val seed: ULong,
) {
    private var state = seed

    fun nextULong(): ULong {
        val out = splitmix64(state)
        state += GAMMA
        return out
    }

    /** A number in `0 until bound`. The tiny modulo bias does not matter for test data. */
    fun nextInt(bound: Int): Int = (nextULong() % bound.toULong()).toInt()

    fun nextLong(bound: Long): Long = (nextULong() % bound.toULong()).toLong()

    /** True with a chance of [percent] in 100. */
    fun chance(percent: Int): Boolean = nextInt(PERCENT) < percent

    fun <T> pick(options: List<T>): T = options[nextInt(options.size)]

    private companion object {
        const val GAMMA = 0x9E3779B97F4A7C15uL
        const val PERCENT = 100
    }
}

/** What [GeneratedLibrary.fill] made: the libraries ticked, and cut-offs worth reading it at. */
internal class GeneratedLibrary(
    val libraries: List<ItemId>,
    val cutoffs: List<Instant>,
)

/**
 * Fills [ALICE]'s account with a random library history (spec 10, Unit 3): items in two libraries that are written,
 * edited, moved between libraries, tombstoned and revived over a few hours, and that include what the scheduler
 * refuses (a missing runtime, a malformed date, a negative season, a movie with series data, a kind that is not a
 * movie or an episode, an ID that is not canonical, the same ID in two spellings). The edits touch the rating
 * (null, `""`, `" "` and real ones), the genres, the tags and the season, so a `RuleItem` has something to carry.
 *
 * It also writes series (edited, deleted and revived) and collections with members of every kind, some of them items
 * the library does not hold. It is the corpus of `RuleSnapshotItemsMatchScheduleItemsTest`; the later property tests
 * extend it.
 */
internal object RuleLibraryGen {
    val LIBRARY_A = ItemId(hexId(0xA001))
    val LIBRARY_B = ItemId(hexId(0xA002))

    private val SERIES = (0 until 3).map { hexId(0x900 + it) }
    private val SEASONS = (0 until 4).map { hexId(0x800 + it) }
    private val COLLECTIONS = (0 until 3).map { hexId(0xC00 + it) }
    private val RATINGS = listOf(null, "", " ", "G", "PG", "TV-14", "R")
    private val GENRES = listOf("Drama", "Comedy", "Horror", "Action", "Sci-Fi")
    private val TAGS = listOf("a", "b", "c", "noir", "new")
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Generates one library into [repository], moving [clock] as it goes, and returns what to read it with. */
    suspend fun fill(
        repository: RoomLibraryRepository,
        clock: FakeClock,
        gen: SeededGen,
    ): GeneratedLibrary {
        repository.forget(ALICE)
        repository.useAccount(ALICE)
        val libraries = listOf(LIBRARY_A, LIBRARY_B)
        repository.select(ALICE, libraries.toSet())
        val start = EPOCH.toEpochMilliseconds()
        val ids = idsOf(gen)
        val library = ids.associateWith { if (gen.chance(60)) LIBRARY_A else LIBRARY_B }.toMutableMap()
        val times = ArrayList<Long>()
        var at = start
        repeat(1 + gen.nextInt(MAX_ROUNDS)) {
            clock.atMs(at)
            times += at
            for (id in ids) {
                if (!gen.chance(WRITE_PERCENT)) continue
                if (gen.chance(MOVE_PERCENT)) library[id] = other(library.getValue(id))
                repository.put(ALICE, library.getValue(id), randomItem(gen, id, start))
            }
            if (gen.chance(DELETE_PERCENT)) repository.tombstone(ALICE, listOf(gen.pick(ids)))
            if (gen.chance(REVIVE_PERCENT)) repository.revive(ALICE, listOf(gen.pick(ids)))
            writeSeries(repository, gen)
            writeCollections(repository, gen, ids)
            at += 1 + gen.nextLong(3 * HOUR_MS)
        }
        return GeneratedLibrary(libraries, cutoffsAround(gen, start, times.last()))
    }

    // Series metadata edits, tombstones and revivals, as the series pass makes them.
    private suspend fun writeSeries(
        repository: RoomLibraryRepository,
        gen: SeededGen,
    ) {
        for (show in SERIES) {
            if (!gen.chance(SERIES_WRITE_PERCENT)) continue
            val genres = List(gen.nextInt(3)) { gen.pick(GENRES) }
            val tags = List(gen.nextInt(3)) { gen.pick(TAGS) }
            repository.putSeries(ALICE, LIBRARY_A, series(show, gen.pick(RATINGS), genres, tags))
        }
        if (gen.chance(DELETE_PERCENT)) repository.tombstoneSeries(ALICE, listOf(ItemId(gen.pick(SERIES))))
        if (gen.chance(REVIVE_PERCENT)) repository.reviveSeries(ALICE, listOf(ItemId(gen.pick(SERIES))))
    }

    // Collections with members of every kind, some of them items the library does not hold.
    private suspend fun writeCollections(
        repository: RoomLibraryRepository,
        gen: SeededGen,
        ids: List<ItemId>,
    ) {
        for (box in COLLECTIONS) {
            if (!gen.chance(COLLECTION_WRITE_PERCENT)) continue
            val members =
                List(gen.nextInt(MAX_MEMBERS)) {
                    val held = gen.chance(HELD_MEMBER_PERCENT)
                    val member = if (held) gen.pick(ids).value else hexId(0xF00 + gen.nextInt(MAX_MEMBERS))
                    cm(
                        member,
                        gen.pick(
                            listOf(
                                CollectionMemberKind.Movie,
                                CollectionMemberKind.Episode,
                                CollectionMemberKind.Series,
                                CollectionMemberKind.Season,
                            ),
                        ),
                    )
                }
            repository.writeCollection(ALICE, ItemId(box), "Name of $box", members)
        }
        if (gen.chance(DELETE_PERCENT)) repository.tombstoneCollections(ALICE, listOf(ItemId(gen.pick(COLLECTIONS))))
    }

    // Mostly canonical IDs; now and then an ID that is not one, and a second spelling of another item's ID.
    private fun idsOf(gen: SeededGen): List<ItemId> {
        val count = MIN_ITEMS + gen.nextInt(MAX_ITEMS - MIN_ITEMS)
        val ids = ArrayList<ItemId>()
        for (n in 0 until count) {
            val canonical = hexId(0x100 + n)
            ids +=
                when {
                    gen.chance(NOT_CANONICAL_PERCENT) -> ItemId("not-canonical-$n")
                    n > 0 && gen.chance(SECOND_SPELLING_PERCENT) -> ItemId(spelled(hexId(0x100 + gen.nextInt(n))))
                    else -> ItemId(canonical)
                }
        }
        return ids.distinct()
    }

    // The 8-4-4-4-12 form in upper case: the same ID to CanonicalId, a different row to the database.
    private fun spelled(hex: String) =
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
            .uppercase()

    private fun cutoffsAround(
        gen: SeededGen,
        start: Long,
        last: Long,
    ): List<Instant> {
        val span = last - start + 2 * HOUR_MS
        val cutoffs = List(CUTOFFS) { start - HOUR_MS + gen.nextLong(span) } + (last + 1) + (start - DAY_MS)
        return cutoffs.map(Instant::fromEpochMilliseconds)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per way an item can be odd: a flat table of chances.
    private fun randomItem(
        gen: SeededGen,
        id: ItemId,
        start: Long,
    ): MediaItem {
        val episode = gen.chance(EPISODE_PERCENT)
        val kind =
            when {
                gen.chance(OTHER_KIND_PERCENT) -> gen.pick(listOf(ItemKind.Series, ItemKind.Folder, ItemKind.Other))
                episode -> ItemKind.Episode
                else -> ItemKind.Movie
            }
        val series = if (episode || gen.chance(ODD_PERCENT)) gen.pick(SERIES + "series-not-an-id") else null
        return MediaItem(
            id = id,
            name = "Name ${gen.nextInt(NAMES)}",
            kind = kind,
            runtime = if (gen.chance(ODD_PERCENT)) gen.pick(listOf(null, 0L, -5L))?.milliseconds else runtimeOf(gen),
            parentId = null,
            dateCreated = if (gen.chance(ODD_PERCENT)) null else createdBefore(gen, start),
            premiereDate = if (gen.chance(ODD_PERCENT)) gen.pick(listOf("2001-13-01", "last week")) else dateOf(gen),
            productionYear = if (gen.chance(ODD_PERCENT)) gen.pick(listOf(0, 10_000)) else yearOf(gen),
            officialRating = gen.pick(RATINGS),
            genres = List(gen.nextInt(3)) { gen.pick(GENRES) },
            tags = List(gen.nextInt(3)) { gen.pick(TAGS) },
            seriesId = series?.let(::ItemId),
            seasonId = if (episode && gen.chance(SEASON_PERCENT)) ItemId(gen.pick(SEASONS)) else null,
            seasonNumber = if (episode || series != null) numberOf(gen) else null,
            episodeNumber = if (episode || series != null) numberOf(gen) else null,
        )
    }

    private fun other(library: ItemId) = if (library == LIBRARY_A) LIBRARY_B else LIBRARY_A

    private fun createdBefore(
        gen: SeededGen,
        start: Long,
    ) = Instant.fromEpochMilliseconds(start - 1 - gen.nextLong(DAY_MS))

    private fun runtimeOf(gen: SeededGen) = ((1 + gen.nextInt(MAX_MINUTES)) * 60_000L).milliseconds

    private fun numberOf(gen: SeededGen) = if (gen.chance(ODD_PERCENT)) -1 else gen.nextInt(MAX_NUMBER)

    private fun dateOf(gen: SeededGen) =
        if (gen.chance(DATE_PERCENT)) "${1950 + gen.nextInt(YEARS)}-0${1 + gen.nextInt(9)}-1${gen.nextInt(9)}" else null

    private fun yearOf(gen: SeededGen) = if (gen.chance(DATE_PERCENT)) 1950 + gen.nextInt(YEARS) else null

    private const val MIN_ITEMS = 6
    private const val MAX_ITEMS = 14
    private const val MAX_ROUNDS = 4
    private const val WRITE_PERCENT = 55
    private const val MOVE_PERCENT = 15
    private const val DELETE_PERCENT = 35
    private const val REVIVE_PERCENT = 40
    private const val NOT_CANONICAL_PERCENT = 4
    private const val SECOND_SPELLING_PERCENT = 6
    private const val EPISODE_PERCENT = 35
    private const val OTHER_KIND_PERCENT = 4
    private const val ODD_PERCENT = 4
    private const val SEASON_PERCENT = 80
    private const val DATE_PERCENT = 60
    private const val NAMES = 1000
    private const val MAX_MINUTES = 180
    private const val MAX_NUMBER = 20
    private const val YEARS = 70
    private const val SERIES_WRITE_PERCENT = 40
    private const val COLLECTION_WRITE_PERCENT = 50
    private const val MAX_MEMBERS = 8
    private const val HELD_MEMBER_PERCENT = 60
    private const val CUTOFFS = 3
}
