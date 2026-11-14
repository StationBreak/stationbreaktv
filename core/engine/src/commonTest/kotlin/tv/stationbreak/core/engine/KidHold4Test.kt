// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.cleanStudioName
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.studioKey2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `kid-hold-4` and `grown-up-hold-3` (ADR-0026): the kid studio list is matched by studio fold 2 key, against
 * `docs/rules-format/vectors/kid-hold-4.json`.
 *
 * The `kidHold4` and `grownUpHold3Drops` answers are asserted against the CURRENT [KidHold] and [GrownUpHold]
 * (`kid-hold-5`, `grown-up-hold-4`), on purpose: those versions keep studio fold 2 unchanged, and no row here has an
 * episode whose own rating differs from its series', so the series-rating rule of `kid-hold-5` cannot change an answer.
 */
class KidHold4Test {
    private val file = Json.parseToJsonElement(KidHold4VectorFile.json).jsonObject
    private val cases = file.getValue("cases").jsonArray.map { it.jsonObject }
    private val showId = idOf(0x500)

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.textOrNull(name: String) = getValue(name).jsonPrimitive.contentOrNull

    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean

    private fun JsonObject.names(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun entryOf(case: JsonObject): Pair<RuleItem, RuleSeries?> {
        val seriesJson = case.getValue("series").takeUnless { it is JsonNull }?.jsonObject
        val series =
            seriesJson?.let {
                series(showId, rating = it.textOrNull("officialRating"), studios = it.names("studios"))
            }
        val rating = case.textOrNull("officialRating")
        val studios = case.names("studios")
        val entry =
            when (case.text("kind")) {
                "movie" -> movie(idOf(1), rating = rating, studios = studios)
                else -> episode(idOf(2), showId, rating = rating, studios = studios)
            }
        return entry to series
    }

    // What kid-hold-3 answered for a case: the cases have no genres and no children's rating, so only the studio list
    // decided it, matched by studio fold 1.
    private fun kidHold3(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean {
        val keys = KidHold.KID_STUDIOS.map { foldNameAscii(cleanStudioName(it)) }.toSet()
        return (entry.foldedStudios + series?.foldedStudios.orEmpty()).any { it in keys }
    }

    @Test
    fun everyCaseGivesItsAnswerUnderBothHoldVersions() {
        assertTrue(cases.size >= MIN_CASES, "cases: ${cases.size}")
        assertTrue(KidHold.KID_STUDIOS.containsAll(file.names("kidStudios")))
        for (case in cases) {
            val (entry, series) = entryOf(case)
            val name = case.text("name")
            assertTrue(entry.genres.isEmpty() && !KidHold.isAnimatedFamily(entry, series), name)
            assertEquals(case.flag("kidHold3"), kidHold3(entry, series), "kid-hold-3: $name")
            assertEquals(case.flag("kidHold4"), KidHold.isMadeForKids(entry, series), "kid-hold-4: $name")
            assertEquals(case.flag("grownUpHold3Drops"), GrownUpHold.dropsFromGrownUp(entry, series), "grown-up: $name")
        }
    }

    // A series that holds the look-alike counts for the curation's tone too.
    @Test
    fun aSeriesWithALookAlikeKidStudioIsMadeForKids() {
        assertTrue(KidHold.isSeriesMadeForKids(series(showId, studios = listOf("Cartoon\u3164Network"))))
        assertTrue(KidHold.isSeriesMadeForKids(series(showId, studios = listOf("Nick\u00A0Jr."))))
        assertFalse(KidHold.isSeriesMadeForKids(series(showId, studios = listOf("\u3164"))))
    }

    @Test
    fun everyKidStudioHasANonEmptyKeyThatIsAFixedPointOfFoldTwo() {
        for (studio in KidHold.KID_STUDIOS) {
            val key = studioKey2(studio)
            assertTrue(key.isNotEmpty(), studio)
            assertEquals(key, studioKey2(key), studio)
        }
    }

    // Two entries that differ only by a space now share one key: harmless, they name the same studio.
    @Test
    fun entriesThatDifferOnlyByASpaceShareOneKey() {
        assertEquals(studioKey2("BabyTV"), studioKey2("Baby TV"))
        val shared =
            KidHold.KID_STUDIOS
                .groupBy(::studioKey2)
                .filterValues { it.size > 1 }
                .values
                .map { it.toSet() }
        assertTrue(setOf("BabyTV", "Baby TV") in shared, shared.toString())
    }

    // Every fold-1 match is still a match: fold 2 never splits what fold 1 joins.
    @Test
    fun everyKidStudioSpellingFoldOneMatchedStillMatches() {
        for (studio in KidHold.KID_STUDIOS) {
            for (spelling in listOf(studio, studio.uppercase(), "\u00A0$studio\u3000", "\u200B$studio")) {
                assertTrue(KidHold.isMadeForKids(movie(idOf(1), studios = listOf(spelling)), null), spelling)
            }
        }
    }

    private companion object {
        const val MIN_CASES = 50
    }
}
