// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.scheduler.vectors.DayCases
import tv.stationbreak.core.scheduler.vectors.LineupV2VectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/lineup-v2.json` against `buildDay` and `Day.whatsOn`: whole days of the version 2 orders,
 * the runtime filter and the input errors, from an independent reference (Python, written from docs/rules-format.md
 * only). A case is never changed to suit the code; a disagreement is reported with the case's name.
 */
class LineupV2VectorsTest {
    private val cases = VectorJson.parse(LineupV2VectorFile.text).array("cases").objects()

    @Test
    fun theFileHasAtLeastTwentyCases() {
        assertTrue(cases.size >= 20, "${cases.size} cases")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        DayCases.assertEveryCaseAgrees(cases)
    }

    @Test
    fun everyV2DayTilesItsEpoch() {
        DayCases.assertEveryDayTilesItsEpoch(cases)
    }
}
