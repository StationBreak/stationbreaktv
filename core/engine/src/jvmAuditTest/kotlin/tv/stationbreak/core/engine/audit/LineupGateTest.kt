// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The household-view gate on its frozen fixtures (#1035, docs/design/lineup-seats-and-gaps.md): no hard-fail invariant
 * is broken, and the report of each case is its pinned golden, byte for byte. A change that moves a golden shows as a
 * diff of `core/engine/src/audit/gate/golden`; after reading it, `./gradlew :core:engine:lineupGateGoldens` writes it
 * again.
 */
class LineupGateTest {
    @Test
    fun everyCaseKeepsEveryHardFailInvariantAndMatchesItsGolden() {
        for (case in GateTestSupport.cases) {
            val result = GateTestSupport.run(case)
            assertEquals(emptyList(), result.violations.map { it.toString() }, "${case.key} breaks an invariant")
            val file = File(GateTestSupport.golden, "${case.key}.json")
            assertTrue(file.isFile, "${file.name} is missing: run :core:engine:lineupGateGoldens")
            // The failure names the file and the task; the diff is in git, so the text is not printed.
            val hint = "${file.name} is out of date: run :core:engine:lineupGateGoldens"
            assertEquals(file.readText(), result.report, hint)
        }
    }

    @Test
    fun theGateIsDeterministic() {
        val case = GateTestSupport.case("edge-cases")
        assertEquals(GateTestSupport.run(case).report, GateTestSupport.run(case).report)
    }

    @Test
    fun noGoldenIsLeftOverFromACaseThatWentAway() {
        val expected = GateTestSupport.cases.map { "${it.key}.json" }.toSet()
        assertEquals(
            expected,
            GateTestSupport.golden
                .list()
                .orEmpty()
                .toSet(),
        )
    }

    // A pass that fills no lineup would pass every invariant for want of anything to check.
    @Test
    fun everyCaseHasARealLineupInBothPasses() {
        for (case in GateTestSupport.cases) {
            val report = GateTestSupport.run(case).report
            val rows = report.lines().count { it.startsWith("    {\"key\":") }
            assertTrue(rows >= 2 * MIN_ROWS, "${case.key}: $rows channel rows in two passes")
            assertTrue("\"leftovers:" in report, "${case.key} has no leftovers channel on")
            assertTrue("\"picks\": []" !in report, "${case.key} has no planner picks")
        }
    }

    @Test
    fun theWarningsAreReportedAndNeverFailTheGate() {
        val loud = GateCase("loud", "edge-cases.json", leftoverCapHours = 0)
        val result = GateTestSupport.run(loud)
        assertTrue(result.warnings.any { "over the pinned 0 h" in it }, result.warnings.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun noFixtureHoldsAPathAnAddressOrAKey() {
        for (file in GateTestSupport.fixtures.listFiles().orEmpty()) {
            val text = file.readText()
            for (bad in listOf("http", "/", "\\", "@", "apikey", "token")) {
                assertFalse(bad in text, "${file.name}: $bad")
            }
        }
    }

    private companion object {
        const val MIN_ROWS = 5
    }
}
