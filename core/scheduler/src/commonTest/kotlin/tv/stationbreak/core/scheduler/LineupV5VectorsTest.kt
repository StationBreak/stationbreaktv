// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.scheduler.vectors.DayCases
import tv.stationbreak.core.scheduler.vectors.LineupV5VectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/lineup-v5.json` against `buildDay` and `Day.whatsOn`: whole days of rules format version
 * 5, the spread end of the day, worked out by hand from docs/rules-format.md. A case is never changed to suit the code;
 * a disagreement is reported with the case's name.
 */
class LineupV5VectorsTest {
    private val all = VectorJson.parse(LineupV5VectorFile.text).array("cases").objects()

    private val names = all.map { it.string("name") }

    @Test
    fun theCaseNamesAreUniqueAndEveryCaseIsADay() {
        assertEquals(all.size, names.toSet().size, "case names are unique")
        assertTrue(all.none { it["inputError"] != null }, "version 5 adds no input error")
        assertTrue(all.none { "dayEnd" in it.obj("settings") }, "the file implies the spread end: no dayEnd key")
    }

    @Test
    fun theFileCoversWhatThePlanAsks() {
        val missing = planned.filter { it !in names }
        assertTrue(missing.isEmpty(), "missing cases: $missing")

        fun grid(aligned: Boolean) =
            all.count {
                it
                    .obj("settings")
                    .obj("timing")["alignToGrid"]
                    ?.jsonPrimitive
                    ?.content == "$aligned"
            }
        assertTrue(grid(true) > 0 && grid(false) > 0, "both grid modes")
        val blocks = all.flatMap { it.array("blocks").objects() }
        val breaks = blocks.count { it["break"]?.jsonPrimitive?.content == "true" }
        assertTrue(breaks > 0, "break blocks")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        DayCases.assertEveryCaseAgrees(all, v5 = true)
    }

    @Test
    fun everyV5DayTilesItsEpoch() {
        DayCases.assertEveryDayTilesItsEpoch(all, v5 = true)
    }

    private companion object {
        val planned =
            listOf(
                "spread curated",
                "spread curated same-series short",
                "spread curated collection",
                "spread curated wrapped group",
                "spread all movies",
                "spread one film",
                "spread aired short",
                "spread ordered grouped",
                "spread grid curated",
                "spread grid ordered",
                "spread one-hour epoch",
                "spread shuffle",
                "spread S2 point skipped",
                "spread S2 nearest tie",
                "spread fewer cards than breaks",
                "spread short remainder",
                "spread first fit beats best fit",
                "spread grouped several prefixes",
                "spread unit dropped",
                "spread plan tie",
                "spread repeat gap unshifted",
                "spread grid prefers unaired",
            )
    }
}
