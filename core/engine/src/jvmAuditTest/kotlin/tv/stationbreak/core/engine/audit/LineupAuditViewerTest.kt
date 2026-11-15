// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixtures' values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The viewer's own choices in the audit, and a title no recipe can take. */
class LineupAuditViewerTest {
    private val sitcoms = ProfileLibraries.build("sitcom-household").input.snapshot

    private fun customRules(genre: String) =
        ChannelRules(
            formatVersion = 1,
            name = null,
            seed = Seed(0uL),
            sources = listOf(Source.Genre(genre)),
            exclusions = null,
            maxRating = null,
            ordering = null,
            timing = Timing(null, null, null, null, null, null, BroadcastDay(0, null)),
            filler = null,
        )

    @Test
    fun aTitleThatNoRecipeCanTakeIsSaidToMatchNoSourceAndAirsOnLeftovers() {
        // A genre no recipe names and a year no decade channel covers: nothing matches it.
        val id = CanonicalId.parse("f".repeat(32))
        val item = ScheduleItem(id, ScheduleItemKind.Movie, 6_000_000L, 0L, null, null, 1905, null, null, null)
        val library = sitcoms.libraries.first()
        val lone = RuleItem(item, library, null, "PG", listOf("Quokka"), emptyList(), emptyList(), "Brabra Xifa")
        val report = LineupAudit.run(AuditInput(sitcoms.copy(items = sitcoms.items + lone), null))
        val row = report.titles.single { it.name == "Brabra Xifa" }
        assertNull(row.home)
        assertEquals(Cause.NoSourceMatch, row.cause)
        assertEquals(listOf("leftovers:odds-and-ends"), row.fallback)
        assertEquals(1, report.totals.noHomeByCause[Cause.NoSourceMatch])
    }

    @Test
    fun theViewersSwitchOnAndCustomChannelAreInTheReportAsTheViewers() {
        val viewer = Viewer(listOf("after-hours"), listOf(CustomChannel("My comedies", customRules("Comedy"))))
        val report = LineupAudit.run(AuditInput(sitcoms, viewer))
        val optIn = report.starters.single { it.key == "after-hours" }
        assertTrue(optIn.on)
        assertEquals("viewer", optIn.by)
        val custom = report.totals.titlesPerChannel.single { it.channel == "custom:1" }
        assertTrue(custom.titles > 0)
        assertTrue(report.overlaps.any { setOf(it.a, it.b) == setOf("laugh-track", "custom:1") })
    }

    @Test
    fun anUnknownSwitchOnIsANoteNotAFailure() {
        val report = LineupAudit.run(AuditInput(sitcoms, Viewer(listOf("not-a-recipe"), emptyList())))
        assertTrue(report.notes.any { it.contains("names no starter") })
        assertFalse(report.notes.any { it.contains("not-a-recipe") })
    }
}
