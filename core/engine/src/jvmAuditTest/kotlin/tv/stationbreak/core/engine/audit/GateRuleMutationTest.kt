// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelCeilings
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.ChannelId
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

    // The mutation tests above leak a title into a finished view. These build the view through a weakened path (the
    // real planner and day view, with a channel's policy forced open), so a laxer `HouseholdViews` cannot stay green.
    @Test
    fun aViewBuiltWithAnOpenPolicyOnTheFamilyChannelsFails() {
        val plan = GateTestSupport.everythingOnPlan(leaks.case)
        val real = HouseholdViews.of(plan, GateMain.SCHEDULER_EPOCH)
        assertTrue(leaks.broken(real).isEmpty(), "the real view keeps every rule")
        val weak =
            GateTestSupport.everythingOn(leaks.case) {
                if (ChannelCeilings.of(it, plan.catalog) == "PG") PoolPolicy.Open else null
            }
        val rules = leaks.broken(weak)
        assertTrue(Rule.AdultOnYoungBand in rules || Rule.HorrorVeto in rules, "$rules")
    }

    @Test
    fun aViewBuiltWithAnOpenPolicyOnTheLeftoversFails() {
        val weak =
            GateTestSupport.everythingOn(leaks.case) {
                if (it.source is ChannelSource.Leftovers) PoolPolicy.Open else null
            }
        val rules = leaks.broken(weak)
        assertTrue(Rule.AdultOnYoungBand in rules && Rule.NoMetadataInLeftovers in rules, "$rules")
    }

    // The aired items are the day's epoch cut; a leak in the part of the pool the cut leaves out must still fail.
    @Test
    fun aLeakOutsideTheDaysCutStillFails() {
        val item = leaks.slice("Synthetic Seventies Sexploitation Comedy")
        val key = "level-up"
        assertTrue(
            leaks.view.channels
                .first { it.key == key }
                .items
                .none { it.id == item.id },
        )
        val changed = view.channels.map { if (it.key == key) it.copy(pool = it.pool + item) else it }
        assertTrue(Rule.ExplicitOffAfterHours in leaks.broken(leaks.withChannels(changed)))
    }

    @Test
    fun aCustomChannelWithAPg13CeilingAirsNoTvMaTitle() {
        val tvMa = leaks.episodeOf { it.officialRating == "TV-MA" }
        val id = ChannelId("custom:1")
        val custom = GateChannel("custom", id, 600, false, "PG-13", PoolPolicy.Custom, listOf(tvMa), emptySet())
        assertTrue(Rule.Ceiling in leaks.broken(leaks.withChannels(leaks.view.channels + custom)))
    }

    @Test
    fun aLineupWithAChannelTheGateDoesNotModelIsRefused() {
        val plan = GateTestSupport.everythingOnPlan(leaks.case)
        val hidden = plan.lineup.channels.map { it.copy(state = ChannelState.UserHidden) }
        val lineup = plan.lineup.copy(channels = hidden)
        val changed = PlannedLineup(plan.snapshot, plan.names, lineup, plan.planning, plan.day, emptyList(), true)
        val refused = runCatching { HouseholdViews.of(changed, GateMain.SCHEDULER_EPOCH) }
        assertTrue(
            refused
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("does not model"),
        )
    }
}
