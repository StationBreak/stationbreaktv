// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.NameFolds
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The faster day build (#392) gives the same days as the one it replaced, kept as `LegacyLineupDays`; and a timing
 * harness over a library of 20,000 items and 50 channels. The harness prints its numbers and asserts only a very
 * generous bound (the optimised build is no slower than the old one plus a minute), so a slow CI host cannot fail it.
 */
class DayBuildEquivalenceTest {
    private val days = LineupDays(TestCatalog)
    private val legacy = LegacyLineupDays(TestCatalog)

    private val genres = listOf("Comedy", "Crime", "Western", "Horror", "Science Fiction", "Animation", "Kids", "Drama")
    private val ratings = listOf("G", "PG", "PG-13", "R", "TV-MA", null, "")

    /** [count] movies in canonical-ID order, varied in genre, rating, tag, runtime, age and (some) deletion. */
    private fun library(count: Int): List<RuleItem> =
        (1..count).map { n ->
            val base =
                movie(
                    n,
                    genres[n % genres.size],
                    genres[(n / 7) % genres.size],
                    rating = ratings[(n / 3) % ratings.size],
                    tags = if (n % 11 == 0) listOf("late") else emptyList(),
                    runtimeMs = RUNTIMES[n % RUNTIMES.size],
                    // A few are too new for the cut-off and wait for the next epoch.
                    createdAtMs = if (n % 97 == 0) NOON.toEpochMilliseconds() else ADDED_MS,
                )
            if (n % 89 == 0) base.copy(item = base.item.copy(deletedAtMs = ADDED_MS + 1)) else base
        }

    private fun snapshotOf(
        items: List<RuleItem>,
        cutoffMs: Long,
    ) = RuleSnapshot(cutoffMs, setOf(LIBRARY_ID), items, emptyMap(), emptyMap(), collectionsSynced = true)

    private fun custom(
        n: Int,
        vararg sources: Source,
        start: String? = "06:00",
        maxRating: String? = null,
    ) = channel(
        "custom-$n",
        400 + n,
        ChannelState.On,
        ChannelSource.Custom(rules("Custom $n", *sources, maxRating = maxRating, timing = timing(start = start))),
    )

    /** Starters, a hidden one, a shelved opt-in one, customs (one on another day start) and four leftovers. */
    private fun variedLineup(extraCustoms: Int = 0): Lineup {
        val toons = starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G"))
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        val whodunit = starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime")))
        val westerns = starter("westerns", 220, ChannelState.Shelf, rules("Westerns", Source.Genre("Western")))
        val frights = starter("frights", 310, ChannelState.UserHidden, rules("Frights", Source.Genre("Horror")))
        val after = starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late")))
        val night = custom(1, Source.Genre("Drama"), start = "12:00")
        val rated = custom(2, Source.Genre("Comedy"), Source.Genre("Crime"), maxRating = "PG-13")
        val more = (0 until extraCustoms).map { custom(10 + it, Source.Genre(genres[it % genres.size])) }
        return lineupOf(
            toons,
            laughs,
            whodunit,
            westerns,
            frights,
            after,
            night,
            rated,
            *more.toTypedArray(),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
            leftovers("late-odds", 198, "R"),
            leftovers("hidden-odds", 197, null, ChannelState.UserHidden),
            leftovers("shelf-odds", 196, null, ChannelState.Shelf),
        )
    }

    private fun snapshotsFor(
        lineup: Lineup,
        atMs: Long,
        items: List<RuleItem>,
    ): Pair<List<Scheduled>, Map<Long, RuleSnapshot>> {
        val scheduled = days.scheduled(lineup, atMs)
        // A distinct, smaller library per cut-off, so the claim chains of two cut-offs differ.
        val cutoffs = scheduled.map { it.cutoffMs }.distinct().sorted()
        val snapshots =
            cutoffs.withIndex().associate { (index, cutoff) -> cutoff to snapshotOf(items.dropLast(index * 5), cutoff) }
        return scheduled to snapshots
    }

    private fun describe(days: List<ChannelDay>) =
        days.map {
            listOf(
                it.channel.id,
                it.name,
                it.settings,
                it.n,
                it.pool.map { item -> item.id },
                it.hidden,
                it.ceilingRank,
                // The laid-out day, not just its pool: the same pool and settings give the same blocks.
                it.day.blocks,
            )
        }

    @Test
    fun theFasterBuildGivesTheSameDaysAsTheOldOne() {
        val lineup = variedLineup(extraCustoms = 6)
        val items = library(1_200)
        for (hours in listOf(0L, 5L, 24L, 30L, 49L)) {
            val atMs = NOON.toEpochMilliseconds() + hours * 3_600_000L
            val (scheduled, snapshots) = snapshotsFor(lineup, atMs, items)
            assertTrue(scheduled.map { it.cutoffMs }.distinct().size >= 2, "two cut-offs are in play")
            val expected = describe(legacy.days(lineup, scheduled, snapshots))
            val actual = describe(days.days(lineup, scheduled, snapshots))
            assertEquals(expected, actual, "the days at +${hours}h")
            assertTrue(actual.any { (it[4] as List<*>).isNotEmpty() })
        }
    }

    @Test
    fun leftoversOnDifferentCutoffsClaimPerCutoffAsTheOldBuildDid() {
        val lineup = variedLineup(extraCustoms = 3)
        val items = library(1_200)
        var shift = 0L
        // Leftovers in different epochs (every other one a day on), so their cut-offs and snapshots differ.
        val scheduled =
            days.scheduled(lineup, NOON.toEpochMilliseconds()).map { s ->
                val onLeftovers = s.channel.source is ChannelSource.Leftovers
                if (onLeftovers) Scheduled(s.channel, s.settings, s.n + shift++ % 2) else s
            }
        val leftoversCutoffs = scheduled.filter { it.channel.source is ChannelSource.Leftovers }.map { it.cutoffMs }
        assertEquals(2, leftoversCutoffs.distinct().size, "the leftovers sit on two cut-offs")
        val cutoffs = scheduled.map { it.cutoffMs }.distinct().sorted()
        val snapshots =
            cutoffs.withIndex().associate { (index, cutoff) -> cutoff to snapshotOf(items.dropLast(index * 7), cutoff) }

        val expected = describe(legacy.days(lineup, scheduled, snapshots))
        assertEquals(expected, describe(days.days(lineup, scheduled, snapshots)))
    }

    @Test
    fun theLeftoversAreWalkedOnceAndStillClaimInOrder() {
        val lineup = variedLineup()
        val (scheduled, snapshots) = snapshotsFor(lineup, NOON.toEpochMilliseconds(), library(600))
        val built = days.days(lineup, scheduled, snapshots).associateBy { it.channel.id }
        val kids =
            built
                .getValue(LineupIds.leftovers("kids-odds-and-ends"))
                .pool
                .map { it.id }
                .toSet()
        val late =
            built
                .getValue(LineupIds.leftovers("late-odds"))
                .pool
                .map { it.id }
                .toSet()
        val rest =
            built
                .getValue(LineupIds.leftovers("odds-and-ends"))
                .pool
                .map { it.id }
                .toSet()
        assertTrue(kids.isNotEmpty() && late.isNotEmpty())
        assertTrue((kids intersect late).isEmpty(), "the kids' odds and the late odds share nothing")
        assertTrue((kids intersect rest).isEmpty() && (late intersect rest).isEmpty(), "nor does the rest")
    }

    @Test
    fun aBuildThatIsToldToStopStopsBeforeTheNextChannel() {
        val lineup = variedLineup(extraCustoms = 6)
        val (scheduled, snapshots) = snapshotsFor(lineup, NOON.toEpochMilliseconds(), library(300))
        var calls = 0
        assertFailsWith<IllegalStateException> {
            days.days(lineup, scheduled, snapshots) {
                calls++
                check(calls < 3) { "stop" }
            }
        }
        assertEquals(3, calls, "no channel was worked out after the stop")
    }

    @Test
    fun timingOfTheOldAndTheNewDayBuild() {
        val lineup = variedLineup(extraCustoms = 42)
        val items = library(20_000)
        val (scheduled, snapshots) = snapshotsFor(lineup, NOON.toEpochMilliseconds(), items)
        assertTrue(scheduled.size >= 48, "about 50 channels: ${scheduled.size}")
        // Warm the JIT with both, then take the best of a few runs of each.
        repeat(2) {
            legacy.days(lineup, scheduled, snapshots)
            days.days(lineup, scheduled, snapshots)
        }
        val before = best { legacy.days(lineup, scheduled, snapshots) }
        val after = best { days.days(lineup, scheduled, snapshots) }
        var evaluations = 0
        days.days(lineup, scheduled, snapshots, evaluated = { evaluations++ })
        val folds = NameFolds()
        items.forEach { it.primeFolds(folds) }
        println(
            "NameFolds itemNames=${items.sumOf { it.genres.size + it.tags.size }} " +
                "distinctFoldedNames=${folds.distinctNames} distinctFoldedLists=${folds.distinctLists}",
        )
        println(
            "DayBuildTiming channels=${scheduled.size} items=${items.size} beforeMs=$before afterMs=$after " +
                "evaluations=$evaluations",
        )
        assertEquals(
            describe(legacy.days(lineup, scheduled, snapshots)),
            describe(days.days(lineup, scheduled, snapshots)),
        )
        assertTrue(after <= before + 60_000, "the new build is not slower than the old one by a minute")
    }

    @Test
    fun rulesThatDifferOnlyOutsideThePoolShareOneEvaluationButAKidsCeilingDoesNot() {
        // 12 is in the Kids band, so it is capped to PG; 401 and 402 have the same sources and no ceiling, so they
        // share a pool and differ in their days (number, name, seed).
        val lineup =
            lineupOf(
                custom(0, Source.Genre("Comedy")).copy(defaultNumber = 12),
                custom(1, Source.Genre("Comedy")),
                custom(2, Source.Genre("Comedy")),
            )
        val items = library(600)
        val (scheduled, snapshots) = snapshotsFor(lineup, NOON.toEpochMilliseconds(), items)
        var evaluations = 0
        val actual = days.days(lineup, scheduled, snapshots, evaluated = { evaluations++ })
        assertEquals(describe(legacy.days(lineup, scheduled, snapshots)), describe(actual))
        assertEquals(3, scheduled.size)
        assertEquals(2, evaluations, "the capped channel and the shared pool of the other two")
        assertTrue(evaluations < scheduled.size)
        assertTrue(actual[0].pool.size < actual[1].pool.size, "the PG cap removed items")
        assertEquals(actual[1].pool, actual[2].pool)
        assertTrue(actual[1].name != actual[2].name)
    }

    @Test
    fun capitalisedInheritedNamesMatchThroughTheSharedFolds() {
        val seriesId = id(9_000)
        val episodes =
            (1..4).map { n ->
                RuleItem(
                    item =
                        ScheduleItem(
                            id(8_000 + n),
                            ScheduleItemKind.Episode,
                            1_800_000L,
                            ADDED_MS,
                            null,
                            null,
                            2000,
                            seriesId,
                            1,
                            n,
                        ),
                    libraryId = LIBRARY_ID,
                    seasonId = null,
                    officialRating = "PG",
                    genres = emptyList(),
                    tags = emptyList(),
                )
            }
        val series = RuleSeries(seriesId, "PG", listOf("Science Fiction"), listOf("LATE"))
        val folds = NameFolds()
        (episodes + library(50)).forEach { it.primeFolds(folds) }
        series.primeFolds(folds)
        val lineup =
            lineupOf(
                custom(1, Source.Genre("sCIENCE fICTION")),
                custom(2, Source.Tag("Late")),
            )
        val snapshot =
            RuleSnapshot(
                NOON.toEpochMilliseconds(),
                setOf(LIBRARY_ID),
                library(50) + episodes,
                mapOf(seriesId to series),
                emptyMap(),
                collectionsSynced = true,
            )
        val scheduled = days.scheduled(lineup, NOON.toEpochMilliseconds())
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshot.copy(cutoffMs = it) }
        val built = days.days(lineup, scheduled, snapshots)
        for (day in built) {
            val expected = episodes.map { it.item.id }.toSet()
            assertTrue(day.pool.map { it.id }.containsAll(expected), "${day.name} airs the series' episodes")
        }
    }

    private fun best(build: () -> List<ChannelDay>): Long {
        var fastest = Long.MAX_VALUE
        repeat(RUNS) {
            val began = TimeSource.Monotonic.markNow()
            build()
            fastest = minOf(fastest, began.elapsedNow().inWholeMilliseconds)
        }
        return fastest
    }

    private companion object {
        const val RUNS = 5
        val RUNTIMES = longArrayOf(5_400_000L, 7_200_000L, 3_600_000L, 1_800_000L, 10_800_000L, 60_000L, 9_000_000L)
    }
}
