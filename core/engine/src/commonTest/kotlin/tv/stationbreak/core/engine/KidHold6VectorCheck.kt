// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.engine.VectorJson.array
import tv.stationbreak.core.engine.VectorJson.intOrNull
import tv.stationbreak.core.engine.VectorJson.objects
import tv.stationbreak.core.engine.VectorJson.string
import tv.stationbreak.core.engine.VectorJson.stringOrNull
import tv.stationbreak.core.engine.VectorJson.strings
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `RatingMap`, `RatingTier`, `KidHold` and `GrownUpHold` to every case of `kid-hold-6.json` (the text is given,
 * so the same check runs on the copy in commonTest and on the published file in jvmTest).
 */
internal object KidHold6VectorCheck {
    private val showId = idOf(0x600)
    private const val MIN_ROWS = 100

    fun run(text: String) {
        val doc = VectorJson.parse(text)
        var rows = 0
        for (row in doc.array("ratingMap").objects()) {
            rows++
            val rating = row.stringOrNull("rating")
            assertEquals(row.intOrNull("level"), RatingMap.levelOf(rating), "ratingMap: [$rating]")
        }
        for (row in doc.array("tier").objects()) {
            rows++
            val (entry, series) = entryOf(row)
            val name = row.string("name")
            val tier = RatingTier.of(entry, series)
            assertEquals(row.string("tier"), tier.name.lowercase(), "tier: $name")
            assertEquals(tier == RatingTier.Kids, KidHold.admits(entry, series), "admits: $name")
            assertEquals(tier == RatingTier.Kids, GrownUpHold.dropsFromGrownUp(entry, series), "grown-up: $name")
        }
        for (row in doc.array("passesKidCeiling").objects()) {
            rows++
            val film = movie(idOf(1), rating = row.stringOrNull("rating"), genres = listOf("Kids"))
            val passes = KidHold.passesKidCeiling(film, null, row.stringOrNull("ceiling"))
            assertEquals(row.string("passes").toBooleanStrict(), passes, row.string("name"))
        }
        assertTrue(rows >= MIN_ROWS, "the vector has rows: $rows")
    }

    private fun JsonObject.names(name: String): List<String> = if (name in this) strings(name) else emptyList()

    private fun entryOf(row: JsonObject): Pair<RuleItem, RuleSeries?> {
        val rating = row.stringOrNull("rating")
        val genres = row.names("genres")
        val tags = row.names("tags")
        val studios = row.names("studios")
        return when (val shape = row.string("shape")) {
            "movie" -> {
                movie(idOf(1), rating = rating, genres = genres, tags = tags, studios = studios) to null
            }

            "episodeNoSeriesId" -> {
                episode(idOf(2), null, rating = rating, genres = genres, tags = tags, studios = studios) to null
            }

            "episodeSeriesUnknown" -> {
                episode(idOf(2), showId, rating = rating, genres = genres, tags = tags, studios = studios) to null
            }

            "episode" -> {
                val s = row.getValue("series").jsonObject
                val known =
                    series(
                        showId,
                        rating = s.stringOrNull("rating"),
                        genres = s.names("genres"),
                        tags = s.names("tags"),
                        studios = s.names("studios"),
                    )
                episode(idOf(2), showId, rating = rating, genres = genres, tags = tags, studios = studios) to known
            }

            else -> {
                error("unknown shape $shape")
            }
        }
    }
}
