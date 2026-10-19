// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.rules.VectorJson.array
import tv.stationbreak.core.rules.VectorJson.boolean
import tv.stationbreak.core.rules.VectorJson.intOrNull
import tv.stationbreak.core.rules.VectorJson.objects
import tv.stationbreak.core.rules.VectorJson.string
import tv.stationbreak.core.rules.VectorJson.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Normalisation and eligibility against `docs/rules-format/vectors/rating.json`, on every target. The expected values
 * come from an independent reference built on Jellyfin 12.1's own US rating table.
 */
class RatingVectorsTest {
    private val file = VectorJson.parse(RatingVectorFiles.rating)

    @Test
    fun theFileLadderIsTheCodeLadder() {
        val rows = file.array("ladder").objects()
        val fromFile = rows.map { row -> row.array("ratings").map { it.jsonPrimitive.content } }
        assertEquals(fromFile, RatingLadder.tiers)
    }

    @Test
    fun everyStringNormalisesAndRanksAsTheFileSays() {
        val rows = file.array("normalise").objects()
        assertTrue(rows.size >= 90, "the file has the whole of Jellyfin's US table and the awkward cases")
        for (row in rows) {
            val input = row.string("input")
            assertEquals(row.stringOrNull("normalised"), RatingLadder.normalise(input), "normalise '$input'")
            assertEquals(row.intOrNull("rank"), RatingLadder.rankOf(input), "rank '$input'")
        }
    }

    @Test
    fun everyCeilingAdmitsAndBlocksAsTheFileSays() {
        val rows = file.array("eligibility").objects()
        assertEquals(RatingLadder.names.size, rows.size, "one row per ceiling")
        for (row in rows) {
            val ceiling = row.string("maxRating")
            for (item in row.array("eligible")) {
                assertTrue(RatingEligibility.isEligible(item.stringOrNull(), ceiling), "'$item' under $ceiling")
            }
            for (item in row.array("blocked")) {
                assertTrue(!RatingEligibility.isEligible(item.stringOrNull(), ceiling), "'$item' under $ceiling")
            }
        }
    }

    @Test
    fun inheritanceFollowsTheFile() {
        val rows = file.array("inheritance").objects()
        assertTrue(rows.size >= 40)
        val states = mutableSetOf<String>()
        for (row in rows) {
            val own = row.stringOrNull("own")
            val state = row.string("seriesState")
            states += state
            // 'series' is read only when the state is "known"; the other states carry no series.
            val series =
                when (state) {
                    "known" -> SeriesRating.Known(row.stringOrNull("series"))
                    "unknown" -> SeriesRating.Unknown
                    "none" -> SeriesRating.NoSeries
                    else -> error("unknown seriesState '$state'")
                }
            if (state != "known") assertTrue("series" !in row, "a $state row has no series: $row")
            val ceiling = row.stringOrNull("maxRating")
            val actual = RatingEligibility.isEligible(own, series, ceiling)
            assertEquals(row.boolean("eligible"), actual, "own=$own series=$series ceiling=$ceiling")
        }
        assertEquals(setOf("known", "unknown", "none"), states)
    }
}
