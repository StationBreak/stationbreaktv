// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frozen fixtures are what their generators make (the synthetic adult slice is the rating-safety tests' own), and
 * every title in them is invented or marked SYNTHETIC. `:core:engine:lineupGateGoldens` writes the files again.
 */
class GateFixturesTest {
    @Test
    fun eachGeneratedFixtureIsItsFrozenFile() {
        for ((key, input) in GateFixtureSources.all()) {
            val file = GateTestSupport.fixtures.resolve("$key.json")
            assertTrue(file.isFile, "$key.json is missing: run :core:engine:lineupGateGoldens")
            val hint = "$key.json is out of date: run :core:engine:lineupGateGoldens"
            assertEquals(file.readText(), SnapshotWriter.write(input), hint)
        }
    }

    @Test
    fun everyTitleIsInventedSyntheticOrAnEdgeCaseNameWrittenHere() {
        val allowed = Regex("""Synthetic .*|RiffTrax: Invented Feature \d|Episode \d+""")
        for (case in GateTestSupport.cases) {
            val snapshot = GateMain.inputOf(GateTestSupport.fixtures, case).snapshot
            val names = snapshot.items.mapNotNull { it.name } + snapshot.series.values.mapNotNull { it.name }
            for (name in names) {
                val invented = InventedNames.isInvented(name) || allowed.matches(name)
                assertTrue(invented, "${case.key} holds a name that is not invented")
            }
        }
    }
}
