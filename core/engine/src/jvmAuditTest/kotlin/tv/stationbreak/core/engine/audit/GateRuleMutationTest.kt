// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertTrue

/** The other hard-fail rules of the gate, each broken on purpose in the real everything-on view ([Leaks]). */
class GateRuleMutationTest {
    private val leaks = Leaks()
    private val view = leaks.view

    @Test
    fun aHorrorFamilySeriesOnAFamilyChannelFails() {
        val horror = leaks.episodeOf { it.genres == listOf("Horror", "Family") }
        val family = view.channels.first { !it.leftovers && it.number in 34..66 }.key
        assertTrue(Rule.HorrorVeto in leaks.broken(leaks.leaking(family, horror)))
    }

    @Test
    fun anUnratedKidsSeriesOnAKidsChannelFails() {
        val unrated = leaks.episodeOf { it.officialRating == "NR" && "Kids" in it.genres }
        val kids = view.channels.first { !it.leftovers && it.number in 1..33 }.key
        val rules = leaks.broken(leaks.leaking(kids, unrated))
        assertTrue(Rule.UnratedOnYoungBand in rules && Rule.Ceiling in rules, "$rules")
    }

    @Test
    fun aTitleAboveTheChannelsCeilingFails() {
        val adult = leaks.slice("Synthetic Detective Erotic Thriller")
        val teen = view.channels.first { it.ceiling == "TV-14" && !it.leftovers }.key
        assertTrue(Rule.Ceiling in leaks.broken(leaks.leaking(teen, adult)))
    }

    @Test
    fun afterHoursAmongTheDefaultPicksFails() {
        assertTrue(Rule.AfterHoursInDefault in leaks.broken(view, picks = listOf("after-hours")))
        val closed = GateCase("closed", leaks.case.file, afterHours = false)
        assertTrue(Rule.AfterHoursInDefault in leaks.broken(view, closed), "After Hours on with no opt-in")
    }

    @Test
    fun anExplicitTitleWithoutItsHomeOnAfterHoursFails() {
        assertTrue(Rule.ExplicitNotAtHome !in leaks.broken(view), "the real view keeps every Explicit title at home")
        val unhomed = HouseholdView(view.snapshot, view.channels, null)
        assertTrue(Rule.ExplicitNotAtHome in leaks.broken(unhomed))
    }

    @Test
    fun anEmptyKidsFixtureFails() {
        val none =
            view.channels.map {
                if (!it.leftovers && it.number in 1..33) with(leaks) { it.empty() } else it
            }
        assertTrue(Rule.KidsBandEmpty in leaks.broken(leaks.withChannels(none)))
    }

    @Test
    fun aChangedLeftoverCountIsAGoldenDiff() {
        val result = GateTestSupport.run(GateTestSupport.case("edge-cases"))
        val moved = result.report.replaceFirst("\"key\":\"leftovers:", "\"key\":\"leftovers-x:")
        assertTrue(moved != result.report)
        assertTrue(GateTestSupport.golden.resolve("edge-cases.json").readText() != moved)
    }
}
