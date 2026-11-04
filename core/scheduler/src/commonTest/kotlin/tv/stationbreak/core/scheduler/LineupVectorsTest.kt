// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.scheduler.vectors.DayCases
import tv.stationbreak.core.scheduler.vectors.LineupVectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/lineup.json` against `buildDay` and `Day.whatsOn`: whole days from an independent
 * reference (Python, written from docs/rules-format.md only). Every block of every day is checked, every query, and
 * every time outside the day must be an error. A case is never changed to suit the code; a disagreement is reported
 * with the case's name.
 */
class LineupVectorsTest {
    private val cases = VectorJson.parse(LineupVectorFile.text).array("cases").objects()

    @Test
    fun theFileHasAtLeastFiftyCases() {
        assertTrue(cases.size >= 50, "${cases.size} cases")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        DayCases.assertEveryCaseAgrees(cases)
    }

    @Test
    fun everyDayTilesItsEpochWithNothingCrossingTheBoundary() {
        DayCases.assertEveryDayTilesItsEpoch(cases)
    }

    @Test
    fun aTimeOutsideTheDayThrows() {
        val day = buildDay(testSettings(), emptyList(), 0L, noFilms(), noWeights(), noKeeps())
        assertFailsWith<IllegalArgumentException> { day.whatsOn(day.endMs, 0) }
    }
}
