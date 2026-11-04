// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.scheduler.vectors.DayCases
import tv.stationbreak.core.scheduler.vectors.LineupV3VectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/lineup-v3.json` against `buildDay` and `Day.whatsOn`: whole days of the `curated` order,
 * the per-kind runtime windows and the trim window, and the version 3 input errors, from an independent reference
 * (Python, written from docs/rules-format.md only). A case is never changed to suit the code; a disagreement is
 * reported with the case's name.
 */
class LineupV3VectorsTest {
    private val all = VectorJson.parse(LineupV3VectorFile.text).array("cases").objects()
    private val days = all.filter { it["inputError"] == null }
    private val errors = all.filter { it["inputError"] != null }

    private fun named(part: String) = days.filter { part in it.string("name") }

    @Test
    fun theFileHasAtLeastTwentyFourCases() {
        assertTrue(days.size >= 24, "${days.size} whole days")
        assertEquals(all.size, all.map { it.string("name") }.toSet().size, "case names are unique")
        assertTrue(errors.size >= 6, "${errors.size} input-error cases")
    }

    @Test
    fun theFileCoversWhatThePlanAsks() {
        fun grid(aligned: Boolean) =
            days.count {
                it
                    .obj("settings")
                    .obj("timing")["alignToGrid"]
                    ?.jsonPrimitive
                    ?.content == "$aligned"
            }
        assertTrue(grid(true) > 0 && grid(false) > 0, "both grid modes")
        assertTrue(named("planned end").isNotEmpty(), "the planned end")
        assertTrue(named("short pool").isNotEmpty(), "a short pool")
        assertTrue(named("runtime windows per kind").size >= 3, "per-kind window edges")
        assertTrue(named("trim window").size >= 4, "trim-window days")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        DayCases.assertEveryCaseAgrees(all)
    }

    @Test
    fun everyV3DayTilesItsEpoch() {
        DayCases.assertEveryDayTilesItsEpoch(all)
    }

    @Test
    fun everyInputErrorCaseIsRejected() {
        for (case in errors) {
            assertTrue(DayCases.failuresOf(case).isEmpty(), DayCases.failuresOf(case).joinToString())
        }
    }
}
