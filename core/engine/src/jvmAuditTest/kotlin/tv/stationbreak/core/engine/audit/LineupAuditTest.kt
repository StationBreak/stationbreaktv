// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixtures' values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.StarterCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LineupAuditTest {
    private fun auditOf(key: String): AuditReport = LineupAudit.run(ProfileLibraries.build(key).input)

    @Test
    fun theReportSaysItIsThePlannersViewAndNotABuiltDay() {
        val report = auditOf("sitcom-household")
        assertTrue(report.scope.contains("planner's view"))
        assertTrue(report.scope.contains("no rating ceilings"))
        assertTrue(report.scope.contains("late-on"))
        assertTrue(AuditText.render(report).contains("SCOPE: ${report.scope}"))
        assertTrue(AuditJson.render(report).contains("\"scope\":"))
    }

    @Test
    fun everyStarterOfTheCatalogHasARowWithItsStateAndReason() {
        val report = auditOf("sitcom-household")
        assertEquals(StarterCatalog.recipes.size, report.starters.size)
        val laughTrack = report.starters.single { it.key == "laugh-track" }
        assertTrue(laughTrack.on)
        assertEquals("planner", laughTrack.by)
        assertEquals(null, laughTrack.shelfReason)
        assertNotNull(laughTrack.selection?.score)
        assertTrue((laughTrack.onAir?.titles ?: 0) > 0)
        // A shelved starter says why.
        val off = report.starters.filter { !it.on }
        assertTrue(off.isNotEmpty() && off.all { it.shelfReason != null }, "Every starter that is off has a reason")
    }

    @Test
    fun theHorrorShelfThatIsTooSmallLandsElsewhereAndTheReportSaysWhy() {
        val report = auditOf("horror-fan")
        val horror = report.starters.single { it.key == "midnight-projector" }
        assertFalse(horror.on)
        assertEquals("TooThin", horror.shelfReason)
        val rows = report.titles.filter { it.name in CaseTitles.SMALL_HORROR_SHELF }
        assertEquals(CaseTitles.SMALL_HORROR_SHELF.size, rows.size)
        assertTrue(rows.none { it.home?.channel == "midnight-projector" })
    }

    @Test
    fun theAdultAnimatedSitcomIsNotHomedOnTheSitcomChannels() {
        val report = auditOf("sitcom-household")
        val row = report.titles.single { it.name == CaseTitles.ADULT_ANIMATED_SITCOM }
        assertTrue(row.home?.channel !in setOf("laugh-track", "cartoons-after-dark"))
        assertEquals("Empty", report.starters.single { it.key == "cartoons-after-dark" }.shelfReason)
    }

    @Test
    fun theTotalsAgreeWithTheRows() {
        for (key in ProfileLibraries.keys) {
            val report = auditOf(key)
            val noHome = report.titles.count { it.home == null }
            assertEquals(
                noHome,
                report.totals.noHomeByCause.values
                    .sum(),
                key,
            )
            assertEquals(report.titles.size - noHome, report.totals.homed, key)
            assertEquals(
                report.titles.size,
                report.totals.channelsPerTitle.values
                    .sum(),
                key,
            )
            // A guest spot is never the title's home.
            assertTrue(report.titles.all { t -> t.guestSpots.none { it == t.home?.channel } }, key)
        }
    }

    @Test
    fun theRunnerUpIsNeverTheHomeAndScoresNoMoreUnlessTheHomeIsGuestOnly() {
        val report = auditOf("broad-1900")
        for (t in report.titles) {
            val home = t.home
            val second = t.runnerUp
            if (home == null || second == null) continue
            val secondIsDecade = second.terms.q16[DECADE_TERM] < 0
            val homeIsDecade = home.terms.q16[DECADE_TERM] < 0
            val ordered = second.terms.total <= home.terms.total || (secondIsDecade && !homeIsDecade)
            assertTrue(home.channel != second.channel && ordered, "A runner-up outscored a home")
        }
    }

    @Test
    fun withTooLittleForALineupNothingIsOnAndTheTitlesSayWhere() {
        val report = auditOf("anime-viewer")
        assertTrue(report.notes.any { it.contains("Not finalised") })
        assertTrue(report.starters.none { it.on })
        assertEquals(report.titles.size, report.totals.noHomeByCause[Cause.OnlyOnShelvedChannels])
    }

    private companion object {
        const val DECADE_TERM = 4
    }
}
