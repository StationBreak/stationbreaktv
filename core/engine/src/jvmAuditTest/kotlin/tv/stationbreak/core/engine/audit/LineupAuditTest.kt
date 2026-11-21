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

    // Catalog 5 (#786): the horror starter has the lower bar (6 hours, 4 titles), so four films are enough for a home.
    @Test
    fun theSmallHorrorShelfHasAHomeOnTheHorrorStarter() {
        val report = auditOf("horror-fan")
        val horror = report.starters.single { it.key == "midnight-projector" }
        assertTrue(horror.on)
        assertEquals(null, horror.shelfReason)
        val rows = report.titles.filter { it.name in CaseTitles.SMALL_HORROR_SHELF }
        assertEquals(CaseTitles.SMALL_HORROR_SHELF.size, rows.size)
        assertTrue(rows.all { it.home?.channel == "midnight-projector" })
        // Catalog 7 (#998): Thriller is a filter on Dead Drop, not a source, so one thriller film with no spy or heist
        // tag has no channel of its own. It is a candidate only on a thin channel and airs on the leftovers.
        assertEquals(
            1,
            report.totals.noHomeByCause.values
                .sum(),
        )
        assertEquals(1, report.totals.noHomeByCause[Cause.OnlyOnShelvedChannels])
    }

    // Catalog 5: Cartoons After Dark takes the Animation genre (not Family), with the lower bar of four titles. A
    // household with one adult cartoon still has too little for the channel; a broad library has plenty.
    @Test
    fun theAdultAnimatedSitcomHasAHomeOnCartoonsAfterDarkWhenTheShelfIsBigEnough() {
        val small = auditOf("sitcom-household")
        val row = small.titles.single { it.name == CaseTitles.ADULT_ANIMATED_SITCOM }
        assertEquals(null, row.home, "one adult cartoon and one PG one are too few for the channel")
        assertEquals("TooThin", small.starters.single { it.key == "cartoons-after-dark" }.shelfReason)
        for (key in listOf("broad-1900", "thin-200")) {
            val home = auditOf(key).titles.single { it.name == CaseTitles.ADULT_ANIMATED_SITCOM }.home
            assertEquals("cartoons-after-dark", home?.channel, key)
        }
    }

    @Test
    fun noAnimatedFamilyFilmIsHomedOnCartoonsAfterDark() {
        for (key in ProfileLibraries.keys) {
            val profile = ProfileLibraries.build(key)
            val family =
                profile.input.snapshot.items
                    .filter { "Animation" in it.genres && "Family" in it.genres }
                    .mapNotNull { it.name }
                    .toSet()
            val rows = LineupAudit.run(profile.input).titles.filter { it.name in family }
            assertEquals(family.size, rows.size, key)
            assertTrue(rows.none { it.home?.channel == "cartoons-after-dark" }, key)
        }
    }

    // Catalog 5: Laugh Track takes a sitcom with a Drama genre, and leaves Oddly Funny's twist genres to it.
    @Test
    fun laughTrackTakesADramaSitcomAndNotTheMysteryOne() {
        val report = auditOf("sitcom-household")
        val drama = report.titles.single { it.name == CaseTitles.SITCOM_WITH_A_FREED_GENRE }
        assertEquals("laugh-track", drama.home?.channel)
        val twist = report.titles.single { it.name == CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE }
        // Oddly Funny is off (too thin) in this household, so the tie title keeps the home it had at catalog 4: the
        // decade channel. Laugh Track does not take it.
        assertEquals("flip-phone", twist.home?.channel)
        assertEquals("laugh-track", auditOf("sitcom-household").starters.single { it.key == "laugh-track" }.key)
        for (key in listOf("broad-1900", "thin-200")) {
            val home =
                auditOf(key)
                    .titles
                    .single { it.name == CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE }
                    .home
                    ?.channel
            assertEquals("cold-case-cable", home, key)
        }
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
        // One shelf: fewer than five channels qualify, so the lineup is not finalised.
        val b = LibraryBuilder(9)
        b.shows(8, listOf("Drama"), "TV-14", 44, firstYear = 2010)
        val report = LineupAudit.run(AuditInput(b.build(), null))
        assertTrue(report.notes.any { it.contains("Not finalised") })
        assertTrue(report.starters.none { it.on })
        assertEquals(report.titles.size, report.totals.noHomeByCause[Cause.OnlyOnShelvedChannels])
    }

    private companion object {
        const val DECADE_TERM = 4
    }
}
