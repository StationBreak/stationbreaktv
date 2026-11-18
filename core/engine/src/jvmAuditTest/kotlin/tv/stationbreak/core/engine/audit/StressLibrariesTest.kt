// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the sizes are the libraries'.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * #790: the lineup audit, and so the planner and curation it runs, finishes on libraries far bigger and stranger than
 * any one household's, within a bound, and each stress library is the shape it says. The bound is more than ten
 * times the slowest local run (build and audit about 6 s on a busy machine), so a slow CI runner does not flake it;
 * a regression of that size still fails.
 */
class StressLibrariesTest {
    private val bound = 60.seconds

    private val profiles by lazy { StressLibraries.keys.associateWith { StressLibraries.build(it) } }

    private fun snapshot(key: String): RuleSnapshot = profiles.getValue(key).input.snapshot

    private fun titles(key: String): Int {
        val s = snapshot(key)
        return s.series.size + s.items.count { it.item.seriesId == null }
    }

    @Test
    fun everyStressLibraryGetsThroughTheAuditWithinTheBound() {
        // Titles the audit reports: one-minute films are extras by design (ExtrasFilter), so half that library drops.
        val reported =
            mapOf(
                "stress-broad-10000" to 10_000,
                "stress-broad-25000" to 25_000,
                "stress-long-series" to 10_000,
                "stress-one-genre" to 10_000,
                "stress-no-facts" to 10_000,
                "stress-big-collections" to 10_000,
                "stress-near-identical" to 10_000,
                "stress-extreme-runtimes" to 5_000,
            )
        assertEquals(StressLibraries.keys.toSet(), reported.keys)
        for (key in StressLibraries.keys) {
            val (report, took) = measureTimedValue { LineupAudit.run(StressLibraries.build(key).input) }
            assertTrue(took < bound, "$key: build and audit took $took, over $bound")
            assertEquals(reported.getValue(key), report.titles.size, key)
        }
    }

    @Test
    fun theSizesAreTheOnesTheLibrariesName() {
        assertEquals(10_000, titles("stress-broad-10000"))
        assertEquals(25_000, titles("stress-broad-25000"))
        for (key in StressLibraries.keys - setOf("stress-broad-10000", "stress-broad-25000")) {
            assertEquals(StressLibraries.SHAPE_TITLES, titles(key), key)
        }
    }

    @Test
    fun eachShapeIsTheOneItSays() {
        val episodes = snapshot("stress-long-series").items.groupingBy { it.item.seriesId }.eachCount()
        assertEquals(5_000, episodes.filterKeys { it != null }.values.max())

        // An episode row carries no genres of its own: its series does, as a server sends them.
        val films = snapshot("stress-one-genre").items.filter { it.item.seriesId == null }
        assertTrue(films.isNotEmpty() && films.all { it.genres == listOf("Comedy") })
        assertTrue(snapshot("stress-one-genre").series.values.all { it.genres == listOf("Comedy") })

        val bare = snapshot("stress-no-facts")
        assertTrue(bare.items.all { it.genres.isEmpty() && it.tags.isEmpty() && it.studios.isEmpty() })
        assertTrue(bare.series.values.all { it.genres.isEmpty() && it.tags.isEmpty() && it.studios.isEmpty() })

        val collections = snapshot("stress-big-collections").collections.values
        assertEquals(5, collections.size)
        assertTrue(collections.all { it.movies.size + it.series.size == 2_000 })

        val near = snapshot("stress-near-identical").items.mapNotNull { it.name }
        val stem = near.first().substringBeforeLast(' ')
        assertTrue(near.all { it.substringBeforeLast(' ') == stem })
        assertEquals(
            1,
            snapshot("stress-near-identical")
                .items
                .map { it.item.runtimeMs to it.officialRating }
                .toSet()
                .size,
        )

        val minutes = snapshot("stress-extreme-runtimes").items.map { it.item.runtimeMs / 60_000 }.toSet()
        assertEquals(setOf(1L, 900L), minutes)
    }

    @Test
    fun everyTitleIsInvented() {
        for (key in StressLibraries.keys) {
            val s = snapshot(key)
            val films = s.items.filter { it.item.seriesId == null }
            val names = s.series.values.mapNotNull { it.name } + films.mapNotNull { it.name }
            assertTrue(names.all { InventedNames.isInvented(it) }, "$key: a title is not invented")
        }
    }
}
