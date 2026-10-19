// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** The evaluator against [NaivePool], the reference written from the doc, on 1,000 seeded random inputs. */
class PoolPropertyTest {
    private val names = listOf("Comedy", "comedy", "COMEDY", "Drama", "drama", "Sci-Fi", "Sci-Fi ", "İstanbul")
    private val ratings =
        listOf(null, "", " ", "NR", "Unrated", "G", "PG", "pg", "PG-13", "R", "TV-Y7", "TV-MA", "junk", "NC-17")
    private val ceilings = RatingLadder.names.toList().sorted()
    private val libs = listOf(LIB, idOf(0xB0))
    private val seasons = List(3) { idOf(0x200 + it) }

    private fun <T> SeededGen.maybe(
        percent: Int,
        value: T,
    ): T? = if (chance(percent)) value else null

    private fun SeededGen.someNames(): List<String> = List(nextInt(0..3)) { pick(names) }

    @Test
    fun theEvaluatorEqualsTheNaiveReference() {
        forAllCases("pool", 0x504F4F4CL.toULong(), 1000) { gen ->
            val snap = snapshotOf(gen)
            val rules = rulesOf(gen)
            val pool = PoolEvaluator.evaluate(rules, snap)
            val naive = NaivePool.evaluate(rules, snap)
            assertEquals(naive.items, pool.items, "items")
            assertEquals(naive.matchedBySource, pool.counts.matchedBySource, "matchedBySource")
            assertEquals(naive.excluded, pool.counts.excluded, "excluded")
            assertEquals(naive.blockedByCeiling, pool.counts.blockedByCeiling, "blockedByCeiling")
            assertEquals(naive.episodesWithUnknownSeries, pool.counts.episodesWithUnknownSeries, "unknown series")
            assertEquals(naive.unresolvedSources, pool.counts.unresolvedSources, "unresolvedSources")
            assertEquals(snap.collectionsSynced, pool.counts.collectionsSynced, "collectionsSynced")
        }
    }

    private fun snapshotOf(gen: SeededGen): RuleSnapshot {
        val seriesIds = List(gen.nextInt(1..4)) { idOf(0x100 + it) }
        val series = seriesIds.map { RuleSeries(it, gen.pick(ratings), gen.someNames(), gen.someNames()) }
        // One series is sometimes missing from the map though episodes point to it.
        val held = if (gen.chance(30)) series.drop(1) else series
        val items = List(gen.nextInt(0..40)) { itemOf(gen, idOf(0x1000 + it), seriesIds) }
        val itemIds = items.map { it.item.id }
        val collections = List(gen.nextInt(0..3)) { collectionOf(gen, idOf(0x300 + it), itemIds, seriesIds) }
        return RuleSnapshot(
            cutoffMs = 0L,
            libraries = if (gen.chance(80)) libs.toSet() else setOf(LIB),
            items = items,
            series = held.associateBy { it.id },
            collections = collections.associateBy { it.id },
            collectionsSynced = gen.chance(50),
        )
    }

    private fun itemOf(
        gen: SeededGen,
        id: CanonicalId,
        seriesIds: List<CanonicalId>,
    ): RuleItem {
        val library = gen.pick(libs)
        val rating = gen.pick(ratings)
        val genres = gen.someNames()
        val tags = gen.someNames()
        val year = if (gen.chance(25)) null else gen.nextInt(1985..2005)
        return if (gen.chance(50)) {
            movie(id, library, rating, genres, tags, year, gen.maybe(20, 1_000L))
        } else {
            val series = if (gen.chance(10)) null else gen.pick(seriesIds)
            val season = gen.maybe(60, gen.pick(seasons))
            episode(id, series, season, library, rating, genres, tags, year, gen.maybe(20, 1_000L))
        }
    }

    private fun collectionOf(
        gen: SeededGen,
        id: CanonicalId,
        itemIds: List<CanonicalId>,
        seriesIds: List<CanonicalId>,
    ): RuleCollection {
        // Members are drawn from the same IDs whatever their kind, so a movie ID may sit under "episodes".
        fun members(from: List<CanonicalId>): Set<CanonicalId> =
            if (from.isEmpty()) emptySet() else List(gen.nextInt(0..4)) { gen.pick(from) }.toSet()
        return RuleCollection(id, members(itemIds), members(itemIds), members(seriesIds), members(seasons))
    }

    private fun rulesOf(gen: SeededGen): ChannelRules {
        val seriesIds = List(5) { idOf(0x100 + it) }
        val collectionIds = List(4) { idOf(0x300 + it) }

        fun source(): Source =
            when (gen.nextInt(6)) {
                0 -> {
                    Source.Library(gen.pick(libs + idOf(0xC0)))
                }

                1 -> {
                    Source.Series(gen.pick(seriesIds))
                }

                2 -> {
                    Source.Collection(gen.pick(collectionIds))
                }

                3 -> {
                    Source.Genre(gen.pick(names))
                }

                4 -> {
                    Source.Tag(gen.pick(names))
                }

                else -> {
                    val from = gen.nextInt(1985..2005)
                    Source.YearRange(from, from + gen.nextInt(0..10))
                }
            }
        val sources = List(gen.nextInt(1..4)) { source() }
        val exclusions = List(gen.nextInt(0..3)) { source() }
        val ceiling = if (gen.chance(60)) gen.pick(ceilings) else null
        return rules(sources, exclusions.ifEmpty { if (gen.chance(50)) null else exclusions }, ceiling)
    }
}
