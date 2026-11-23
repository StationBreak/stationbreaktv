// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelCeilings
import tv.stationbreak.core.engine.lineup.ChannelSource
import kotlin.test.Test
import kotlin.test.assertTrue

/** #1108: the custom case of the gate holds a custom channel under a young ceiling to its band's tiers. */
class GateCustomMutationTest {
    private val case = GateTestSupport.case("edge-cases-custom")
    private val plan = GateTestSupport.everythingOnPlan(case)

    private fun broken(view: HouseholdView) = GateInvariants.check("mutation", view, case, emptyList()).map { it.rule }

    // #1108: the custom case's real view keeps its PG and TV-14 channels to their band's tiers; a custom channel built
    // without that rule (its policy forced open) airs a PG-rated horror title and fails.
    @Test
    fun aCustomChannelUnderAYoungCeilingBuiltWithoutTheTierLimitFails() {
        assertTrue(broken(HouseholdViews.of(plan, GateMain.SCHEDULER_EPOCH)).isEmpty())
        val weak =
            GateTestSupport.everythingOn(case) {
                val pg = ChannelCeilings.of(it, plan.catalog) == "PG"
                if (it.source is ChannelSource.Custom && pg) PoolPolicy.Open else null
            }
        val rules = broken(weak)
        assertTrue(Rule.HorrorVeto in rules || Rule.AdultOnYoungBand in rules, "$rules")
    }
}
