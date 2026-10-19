// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A size guard for the single pass: 15,000 items, 500 series, 100 collections (one of 2,000 members) and 256 sources
 * plus 256 exclusions of mixed kinds, built deterministically. It asserts the pool against a precomputed expectation
 * and the wall-clock time against a generous limit. It is a guard against an accidental per-item loop over the rules
 * or over the collection members (which would take minutes), not a benchmark.
 */
class PoolSizeTest {
    private val genreNames = List(40) { "Genre-$it" }
    private val libs = List(3) { idOf(0xA0 + it) }
    private val seriesIds = List(SERIES) { idOf(0x1000 + it) }
    private val ratings = listOf("G", "PG", "PG-13", "R", "TV-Y7", "TV-MA", null, "NR")

    private fun build(): Pair<ChannelRules, RuleSnapshot> {
        val series =
            seriesIds.mapIndexed { i, id ->
                val genres = listOf(genreNames[i % 40], genreNames[(i * 7) % 40])
                RuleSeries(id, ratings[i % ratings.size], genres, listOf("Tag-${i % 25}"))
            }
        val items = List(ITEMS) { i -> itemOf(i) }
        val movieIds = items.filter { it.item.seriesId == null }.map { it.item.id }
        val collections =
            List(COLLECTIONS) { c ->
                // Collection 0 has 2,000 member movies; the others have a handful of members of every kind.
                if (c == 0) {
                    RuleCollection(idOf(0x5000), movieIds.take(2_000).toSet(), emptySet(), emptySet(), emptySet())
                } else {
                    RuleCollection(
                        idOf(0x5000 + c),
                        movieIds.drop(c * 10).take(10).toSet(),
                        emptySet(),
                        setOf(seriesIds[c]),
                        emptySet(),
                    )
                }
            }
        val snapshot =
            RuleSnapshot(
                cutoffMs = 0L,
                libraries = libs.toSet(),
                items = items,
                series = series.associateBy { it.id },
                collections = collections.associateBy { it.id },
                collectionsSynced = true,
            )
        val sources = List(RULES) { sourceOf(it, excluding = false) }
        val exclusions = List(RULES) { sourceOf(it, excluding = true) }
        return rules(sources, exclusions, "PG-13") to snapshot
    }

    private fun itemOf(i: Int): RuleItem {
        val id = idOf(0x10000 + i)
        val library = libs[i % 3]
        val genres = listOf(genreNames[i % 40])
        val tags = listOf("Tag-${i % 25}")
        val year = if (i % 11 == 0) null else 1950 + i % 70
        val rating = ratings[(i / 3) % ratings.size]
        return if (i % 5 < 3) {
            movie(id, library, rating, genres, tags, year)
        } else {
            episode(id, seriesIds[i % SERIES], null, library, rating, genres, tags, year)
        }
    }

    // Sources are broad (a genre, a tag, a decade, a series, a collection). Exclusions are narrow, so most of the pool
    // survives them: a tag or genre that no item has, one year, one series.
    private fun sourceOf(
        n: Int,
        excluding: Boolean,
    ): Source =
        when (n % 6) {
            0 -> if (excluding) Source.Genre("Absent-$n") else Source.Genre("GENRE-${n % 40}")

            1 -> if (excluding) Source.Tag("Absent-$n") else Source.Tag("Tag-${n % 25}")

            2 -> if (excluding) Source.YearRange(1900 + n % 40, 1900 + n % 40) else decade(n)

            3 -> Source.Series(seriesIds[if (excluding) (n * 13) % SERIES else n % SERIES])

            // The 2,000-member collection (0) is a source only, so its members reach the ceiling.
            4 -> Source.Collection(idOf(0x5000 + if (excluding) 1 + n % (COLLECTIONS - 1) else n % COLLECTIONS))

            else -> Source.Library(if (excluding) idOf(0xF0 + n) else libs[n % 3])
        }

    private fun decade(n: Int) = Source.YearRange(1950 + n % 60, 1960 + n % 60)

    @Test
    fun theSinglePassOver15000ItemsAndFiveHundredAndTwelveRulesIsFastAndCorrect() {
        val (rules, snapshot) = build()
        val began = System.nanoTime()
        val pool = PoolEvaluator.evaluate(rules, snapshot)
        val elapsedMs = (System.nanoTime() - began) / NANOS_PER_MS
        println("PoolSizeTest: ${pool.items.size} items in $elapsedMs ms")
        assertEquals(EXPECTED_POOL, pool.items.size, "pool size")
        assertEquals(EXPECTED_EXCLUDED, pool.counts.excluded, "excluded")
        assertEquals(EXPECTED_BLOCKED, pool.counts.blockedByCeiling, "blocked")
        assertTrue(elapsedMs < LIMIT_MS, "took $elapsedMs ms, limit $LIMIT_MS ms")
    }

    // The expectation was worked out by NaivePool (the reference written from the doc) on this exact input, once.
    private companion object {
        const val ITEMS = 15_000
        const val SERIES = 500
        const val COLLECTIONS = 100
        const val RULES = 256
        const val NANOS_PER_MS = 1_000_000L
        const val LIMIT_MS = 2_000L
        const val EXPECTED_POOL = 5_578
        const val EXPECTED_EXCLUDED = 3_020
        const val EXPECTED_BLOCKED = 6_402
    }
}
