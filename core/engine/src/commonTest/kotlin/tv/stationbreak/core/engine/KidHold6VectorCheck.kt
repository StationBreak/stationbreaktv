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
 * for the rows it supersedes), of `kid-hold-10.json`, `kid-hold-11.json`, `kid-hold-12.json`, `kid-hold-13.json` and
 * `kid-hold-14.json` (deltas, the second with 'customCeiling' rows on the mapped level), of `grown-up-hold-9.json` (a
 * delta of 'customCeiling' rows, with genres and tags) and of `kid-hold-8.json`, which adds the 'explicitRating',
 * 'tierLimit' and 'noMetadata' sections (the text is given, so
 * the same check runs on the copy in commonTest and on the published file in jvmTest).
 *
 * The frozen files of the versions before `kid-hold-8` ([explicitIsAdult]) are read as `kid-hold-8` reads them: the
 * Explicit tier was part of their adult tier, and the one rating `kid-hold-8` moves ([MOVED_AT_8]) is held to its new
 * level. Their rows are never edited; the rows a later version supersedes ([SUPERSEDED_AT_9], [SUPERSEDED_AT_10],
 * [SUPERSEDED_AT_11], [SUPERSEDED_AT_12], [REVERSED_AT_13], [SUPERSEDED_AT_14]) are held to their new answer.
 *
 * The name is the first file it read, kept so the history of the callers stays readable: it runs every vector file
 * named above, `kid-hold-7.json` included, not `kid-hold-6.json` only.
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
        setOf("AU-R", "SG-R21", "R21")
    private const val LEVEL_X = 7

    /** The rows of earlier files that `kid-hold-12` supersedes (#1128, `kid-hold-12.json` names them): TH 19. */
    private val SUPERSEDED_AT_12: Set<String> = setOf("TH-19", "TH-19+")

    /**
     * The rows of earlier files that `kid-hold-13` reverses (#987, `kid-hold-13.json` names them): KR 19 and JP 19 and
     * 20 are Adult again (level 6, never Explicit), as `kid-hold-10.json` had them. By rating, for 'ratingMap' (the
     * files of `kid-hold-11` and `kid-hold-12` say 7) and for 'explicitRating', and by tier row name.
     */
    private val REVERSED_AT_13: Set<String> =
        setOf("JP-19", "JP-19+", "JP-20", "JP-20+", "KR-19", "KR-19+", "KR 19", "JP 19", "JP 19+", "JP 20")
    private val TIER_SUPERSEDED_AT_13: Map<String, String> =
        mapOf(
            "KR-19 movie is explicit" to "adult",
            "JP-20 movie is explicit" to "adult",
            "GB-U with a kids genre" to "kids",
            "U (UK) stays unknown until a licensed table lands" to "family",
        )

    // The 'customCeiling' rows of `kid-hold-11.json` whose rating `kid-hold-13` reads (12A, FSK 16): they pass now.
    private val CEILING_SUPERSEDED_AT_13: Set<String> =
        setOf(
            "unknown FSK 16 movie, R ceiling, grown-up",
            "unknown 12A movie, TV-14 ceiling, custom",
            "DE-12/FSK 16 list movie, R ceiling, grown-up (an unknown part is unknown)",
        )
    private const val LEVEL_ADULT = 6

    /**
     * The 'ratingMap' rows of earlier files that `kid-hold-14` supersedes (#1006, `kid-hold-14.json` names them): a
     * minimum age of 17, bare or after a two-letter system (`17`, `17+`, `DE-17`), is level 6, not 5.
     */
    private val SUPERSEDED_AT_14: Regex = Regex("([A-Z]{2}-)?17\\+?")

    /**
     * The 'ratingMap' rows of earlier files that `kid-hold-13` supersedes (#987, `kid-hold-13.json` names them): a bare
     * all-ages word is Family at least (level 2), and the board ratings the XML lacked are read.
     */
    @Suppress("MagicNumber")
    private val SUPERSEDED_AT_13: Map<String?, Int?> =
        mapOf(
            "U" to 2,
            "ALL" to 2,
            "ATP" to 2,
            "GA" to 2,
            "UNIVERSAL" to 2,
            "12A" to 3,
            "GB-12A" to 3,
            "GB-15" to 5,
            "gb/15" to 5,
            "GB:15" to 5,
            "FSK 16" to 5,
            "FSK 0" to 0,
            "DE-FSK 12" to 3,
            "Germany: FSK 18" to 6,
            "JP-PG12" to 3,
            "PG12" to 3,
            "JP-R15+" to 5,
            "R15+" to 5,
            "AU-MA15+" to 5,
            "MA15+" to 5,
            "MA 15+" to 5,
            "DE-6/GB-U" to 1,
            "US:PG-13" to 3,
            "GB-R18" to 7,
        )

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
            val expected = TIER_SUPERSEDED_AT_13[name] ?: TIER_SUPERSEDED_AT_10[name] ?: row.string("tier")
            assertEquals(expected, read.name.lowercase(), "tier: $name")
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
            val expected = row.string("name") in CEILING_SUPERSEDED_AT_13 || row.string("passes").toBooleanStrict()
            assertEquals(expected, passes, "customCeiling: ${row.string("name")}")
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
            assertEquals(levelOf(row, explicitIsAdult), RatingMap.levelOf(rating), "ratingMap: [$rating]")
        }
        for (row in doc.sectionOrEmpty("explicitRating")) {
            rows++
            val rating = row.stringOrNull("rating")
            val written = SUPERSEDED_AT_9[rating] ?: row.string("explicit").toBooleanStrict()
            val adultOnly = rating in SUPERSEDED_AT_11 || rating in SUPERSEDED_AT_12
            val explicit = rating !in REVERSED_AT_13 && (adultOnly || written)
            assertEquals(explicit, RatingMap.isExplicit(rating), "explicit: [$rating]")
        }
        return rows
    }

    // The level a 'ratingMap' row holds the code to: the file's, or the answer of the later version that supersedes it.
    private fun levelOf(
        row: JsonObject,
        explicitIsAdult: Boolean,
    ): Int? {
        val rating = row.stringOrNull("rating")
        return when {
            rating != null && SUPERSEDED_AT_14.matches(rating) -> LEVEL_ADULT
            rating in REVERSED_AT_13 && rating?.contains(' ') == false -> LEVEL_ADULT
            rating in SUPERSEDED_AT_13 -> SUPERSEDED_AT_13[rating]
            rating in SUPERSEDED_AT_11 || rating in SUPERSEDED_AT_12 -> LEVEL_X
            rating in SUPERSEDED_AT_10 -> SUPERSEDED_AT_10[rating]
            explicitIsAdult && rating in MOVED_AT_8 -> MOVED_AT_8[rating]
            else -> row.intOrNull("level")
        }
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
