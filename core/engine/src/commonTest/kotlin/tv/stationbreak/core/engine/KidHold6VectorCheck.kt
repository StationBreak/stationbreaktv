// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.engine.VectorJson.array
import tv.stationbreak.core.engine.VectorJson.intOrNull
import tv.stationbreak.core.engine.VectorJson.objects
import tv.stationbreak.core.engine.VectorJson.string
import tv.stationbreak.core.engine.VectorJson.stringOrNull
import tv.stationbreak.core.engine.VectorJson.strings
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.Source
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `RatingMap`, `RatingTier`, `KidHold` and `GrownUpHold` to every case of `kid-hold-6.json`, of
 * `kid-hold-7.json`, which has the same shape and only 'tier' rows, of `kid-hold-9.json` (a delta, see its description
 * for the rows it supersedes), of `kid-hold-10.json`, `kid-hold-11.json` and `kid-hold-12.json` (deltas, the
 * second with 'customCeiling' rows on the mapped level), of `grown-up-hold-9.json` (a delta of 'customCeiling' rows,
 * with genres and tags) and
 * of `kid-hold-8.json`, which adds the 'explicitRating', 'tierLimit' and 'noMetadata' sections (the text is given, so
 * the same check runs on the copy in commonTest and on the published file in jvmTest).
 *
 * The frozen files of the versions before `kid-hold-8` ([explicitIsAdult]) are read as `kid-hold-8` reads them: the
 * Explicit tier was part of their adult tier, and the one rating `kid-hold-8` moves ([MOVED_AT_8]) is held to its new
 * level. Their rows are never edited; the rows a later version supersedes ([SUPERSEDED_AT_9], [SUPERSEDED_AT_10],
 * [SUPERSEDED_AT_11], [SUPERSEDED_AT_12]) are held to their new answer.
 */
internal object KidHold6VectorCheck {
    private val showId = idOf(0x600)
    private const val MIN_ROWS = 100

    /** The ratingMap rows of the earlier files that `kid-hold-8` moves, with their new level. */
    private val MOVED_AT_8: Map<String?, Int?> = mapOf("X" to 7)

    /** The explicitRating rows of earlier files that `kid-hold-9` supersedes (#1088), with their new answer. */
    private val SUPERSEDED_AT_9: Map<String?, Boolean> = mapOf("\tX" to true)

    /**
     * The rows of earlier files that `kid-hold-10` supersedes (#987, `kid-hold-10.json` names them): a bare age is
     * Family at least (#1006), and `DE-12` is the Android TV XML's DVB 12. By rating, by tier row name and by
     * passesKidCeiling row name.
     */
    @Suppress("MagicNumber")
    private val SUPERSEDED_AT_10: Map<String?, Int?> = mapOf("0" to 2, "6" to 2, "6+" to 2, "7+" to 2, "DE-12" to 3)
    private val TIER_SUPERSEDED_AT_10: Map<String, String> = mapOf("bare 6 with a kids genre" to "family")
    private val CEILING_SUPERSEDED_AT_10: Map<String, Boolean> = mapOf("bare 6 under TV-Y7" to false)

    /**
     * The rows of earlier files that `kid-hold-11` supersedes (#1124, `kid-hold-11.json` names them): the adult-only
     * labels AU R, SG R21, KR 19 and JP 19 and 20 are level 7, Explicit. By rating, for 'ratingMap' and for
     * 'explicitRating'.
     */
    private val SUPERSEDED_AT_11: Set<String> =
        setOf("AU-R", "JP-19", "JP-19+", "JP-20", "JP-20+", "KR-19", "KR-19+", "SG-R21", "R21")
    private const val LEVEL_X = 7

    /** The rows of earlier files that `kid-hold-12` supersedes (#1128, `kid-hold-12.json` names them): TH 19. */
    private val SUPERSEDED_AT_12: Set<String> = setOf("TH-19", "TH-19+")

    fun run(
        text: String,
        minRows: Int = MIN_ROWS,
        explicitIsAdult: Boolean = true,
    ) {
        val doc = VectorJson.parse(text)
        var rows = checkRatings(doc, explicitIsAdult)
        for (row in doc.sectionOrEmpty("tier")) {
            rows++
            val (entry, series) = entryOf(row)
            val name = row.string("name")
            val tier = RatingTier.of(entry, series)
            val read = if (explicitIsAdult && tier == RatingTier.Explicit) RatingTier.Adult else tier
            assertEquals(TIER_SUPERSEDED_AT_10[name] ?: row.string("tier"), read.name.lowercase(), "tier: $name")
            assertEquals(tier == RatingTier.Kids, KidHold.admits(entry, series), "admits: $name")
            val drops = tier == RatingTier.Kids || tier == RatingTier.Explicit
            assertEquals(drops, GrownUpHold.dropsFromGrownUp(entry, series), "grown-up: $name")
        }
        for (row in doc.sectionOrEmpty("tierLimit")) {
            rows++
            val ceiling = row.stringOrNull("ceiling")
            assertEquals(row.string("tier"), GrownUpHold.tierLimit(ceiling).name.lowercase(), "tierLimit: $ceiling")
        }
        for (row in doc.sectionOrEmpty("noMetadata")) {
            rows++
            val (entry, series) = entryOf(row)
            val dropped = GrownUpHold.hasNoMetadata(entry, series)
            assertEquals(row.string("dropped").toBooleanStrict(), dropped, "noMetadata: ${row.string("name")}")
        }
        for (row in doc.sectionOrEmpty("passesKidCeiling")) {
            rows++
            val film = movie(idOf(1), rating = row.stringOrNull("rating"), genres = listOf("Kids"))
            val passes = KidHold.passesKidCeiling(film, null, row.stringOrNull("ceiling"))
            val name = row.string("name")
            assertEquals(CEILING_SUPERSEDED_AT_10[name] ?: row.string("passes").toBooleanStrict(), passes, name)
        }
        for (row in doc.sectionOrEmpty("customCeiling")) {
            rows++
            val passes = customCeilingPasses(row)
            assertEquals(row.string("passes").toBooleanStrict(), passes, "customCeiling: ${row.string("name")}")
        }
        assertTrue(rows >= minRows, "the vector has rows: $rows")
    }

    // The 'ratingMap' and 'explicitRating' rows, each held to its new answer where a later version supersedes it.
    private fun checkRatings(
        doc: JsonObject,
        explicitIsAdult: Boolean,
    ): Int {
        var rows = 0
        for (row in doc.sectionOrEmpty("ratingMap")) {
            rows++
            val rating = row.stringOrNull("rating")
            val level =
                when {
                    rating in SUPERSEDED_AT_11 || rating in SUPERSEDED_AT_12 -> LEVEL_X
                    rating in SUPERSEDED_AT_10 -> SUPERSEDED_AT_10[rating]
                    explicitIsAdult && rating in MOVED_AT_8 -> MOVED_AT_8[rating]
                    else -> row.intOrNull("level")
                }
            assertEquals(level, RatingMap.levelOf(rating), "ratingMap: [$rating]")
        }
        for (row in doc.sectionOrEmpty("explicitRating")) {
            rows++
            val rating = row.stringOrNull("rating")
            val written = SUPERSEDED_AT_9[rating] ?: row.string("explicit").toBooleanStrict()
            val explicit = rating in SUPERSEDED_AT_11 || rating in SUPERSEDED_AT_12 || written
            assertEquals(explicit, RatingMap.isExplicit(rating), "explicit: [$rating]")
        }
        return rows
    }

    // The row's item alone in a snapshot, through the evaluator under the row's policy and ceiling.
    private fun customCeilingPasses(row: JsonObject): Boolean {
        val rating = row.stringOrNull("rating")
        val episodeOf = row.string("shape") == "episode"
        val genres = row.names("genres")
        val tags = row.names("tags")
        val item =
            if (episodeOf) {
                episode(idOf(2), showId, rating = rating, genres = genres, tags = tags)
            } else {
                movie(idOf(2), rating = rating, genres = genres, tags = tags)
            }
        val shows = if (episodeOf) listOf(series(showId, rating = row.stringOrNull("seriesRating"))) else emptyList()
        val policy =
            when (val name = row.string("policy")) {
                "custom" -> PoolPolicy.Custom
                "grownUp" -> PoolPolicy.GrownUp
                "open" -> PoolPolicy.Open
                else -> error("unknown policy $name")
            }
        val channel = rules(listOf(Source.Library(LIB)), maxRating = row.stringOrNull("ceiling"))
        val library = snapshot(items = listOf(item), series = shows)
        return PoolEvaluator.evaluate(channel, library, policy).items.isNotEmpty()
    }

    private fun JsonObject.sectionOrEmpty(name: String): List<JsonObject> = this[name]?.objects().orEmpty()

    private fun JsonObject.names(name: String): List<String> = if (name in this) strings(name) else emptyList()

    private fun JsonObject.providerIds(): Map<String, String> =
        this["providerIds"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

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
                        providerIds = s.providerIds(),
                    )
                episode(idOf(2), showId, rating = rating, genres = genres, tags = tags, studios = studios) to known
            }

            else -> {
                error("unknown shape $shape")
            }
        }
    }
}
