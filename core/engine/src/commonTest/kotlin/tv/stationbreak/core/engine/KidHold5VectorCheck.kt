// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.engine.VectorJson.array
import tv.stationbreak.core.engine.VectorJson.objects
import tv.stationbreak.core.engine.VectorJson.string
import tv.stationbreak.core.engine.VectorJson.stringOrNull
import tv.stationbreak.core.engine.VectorJson.strings
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `KidHold`, `GrownUpHold` and `AnimatedFamilySignal` to every case of `kid-hold-5.json` (the text is given, so
 * the same check runs on the copy in commonTest and on the published file in jvmTest). A row of `animatedFamily` with
 * `"shape": "movie"` is a movie whose own rating is the row's `episode`, with no series.
 */
internal object KidHold5VectorCheck {
    private val showId = idOf(0x500)
    private const val SECTIONS = 5

    fun run(text: String) {
        val doc = VectorJson.parse(text)
        var rows = 0
        for (row in doc.array("passesKidCeiling").objects()) {
            rows++
            val got = KidHold.passesKidCeiling(episodeOf(row), seriesOf(row), row.string("ceiling"))
            assertEquals(row.flag("passes"), got, "passesKidCeiling: ${row.string("name")}")
        }
        for (row in doc.array("animatedFamily").objects()) {
            rows++
            val (entry, series) =
                if (row.stringOrNull("shape") == "movie") {
                    movie(idOf(1), rating = row.stringOrNull("episode"), genres = genres(row, "episodeGenres")) to null
                } else {
                    episodeOf(row) to seriesOf(row)
                }
            assertEquals(row.flag("holds"), KidHold.isAnimatedFamily(entry, series), row.string("name"))
        }
        for (row in doc.array("madeForKids").objects()) {
            rows++
            val made = KidHold.isMadeForKids(episodeOf(row), seriesOf(row))
            assertEquals(row.flag("madeForKids"), made, row.string("name"))
        }
        for (row in doc.array("dropsFromGrownUp").objects()) {
            rows++
            val drops = GrownUpHold.dropsFromGrownUp(episodeOf(row), seriesOf(row))
            assertEquals(row.flag("drops"), drops, row.string("name"))
        }
        for (row in doc.array("unchanged").objects()) {
            rows++
            val rating = row.stringOrNull("rating")
            val shape = row.string("shape")
            val (entry, series) =
                when (shape) {
                    "movie" -> movie(idOf(1), rating = rating) to null
                    "episodeNoSeriesId" -> episode(idOf(2), null, rating = rating) to null
                    "episodeSeriesUnknown" -> episode(idOf(2), showId, rating = rating) to null
                    else -> error("unknown shape $shape")
                }
            val passes = KidHold.passesKidCeiling(entry, series, row.string("ceiling"))
            assertEquals(row.flag("passes"), passes, row.string("name"))
        }
        assertTrue(rows > SECTIONS * 2, "the vector has rows")
    }

    private fun JsonObject.flag(name: String): Boolean = string(name).toBooleanStrict()

    private fun genres(
        row: JsonObject,
        name: String,
    ): List<String> = if (name in row) row.strings(name) else emptyList()

    private fun episodeOf(row: JsonObject): RuleItem =
        episode(idOf(2), showId, rating = row.stringOrNull("episode"), genres = genres(row, "episodeGenres"))

    private fun seriesOf(row: JsonObject): RuleSeries =
        series(showId, rating = row.stringOrNull("series"), genres = genres(row, "seriesGenres"))
}
