// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.ratingsafety.SyntheticAdultSlice
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.AdultMark
import java.io.File
import kotlin.test.assertTrue

/** The gate's folders (set by the `jvmAuditTest` task) and the runs the gate's tests share. */
internal object GateTestSupport {
    val fixtures = File(System.getProperty("gate.fixtures.dir") ?: error("The gate fixture folder is not set"))
    val golden = File(System.getProperty("gate.golden.dir") ?: error("The gate golden folder is not set"))

    val cases: List<GateCase> get() = GateMain.casesIn(fixtures)

    fun case(key: String): GateCase = cases.single { it.key == key }

    fun run(case: GateCase): GateResult = LineupGate.run(case, GateMain.inputOf(fixtures, case))

    /** The everything-on pass of [case] as a view, for a test that breaks it on purpose. */
    fun everythingOn(case: GateCase): HouseholdView {
        val snapshot = ExtrasFilter.filter(GateMain.inputOf(fixtures, case).snapshot).snapshot
        val names = LineupAudit.collectionNamesOf(snapshot)
        val base = PlannedLineup.of(snapshot, names, null)
        val on =
            base.lineup.channels
                .filter { it.state == ChannelState.On }
                .mapNotNull { (it.source as? ChannelSource.Starter)?.recipeKey }
        val keys = StarterCatalog.recipes.filter { !it.optIn && it.key !in on }.map { it.key }
        val optIn = if (case.afterHours) listOf(AdultMark.AFTER_HOURS_KEY) else emptyList()
        return HouseholdViews.of(PlannedLineup.of(snapshot, names, Viewer(keys + optIn, emptyList())))
    }
}

/** The leaks a mutation test makes: the everything-on view of the edge cases, a title put where it must not air. */
internal class Leaks {
    val case: GateCase = GateTestSupport.case("edge-cases-after-hours")
    val view: HouseholdView = GateTestSupport.everythingOn(case)

    /** The view with [item] airing on [channel] as well. */
    fun leaking(
        channel: String,
        item: ScheduleItem,
    ): HouseholdView {
        assertTrue(view.channels.any { it.key == channel }, "$channel is on in the everything-on pass")
        return withChannels(view.channels.map { if (it.key == channel) it.copy(items = it.items + item) else it })
    }

    fun withChannels(channels: List<GateChannel>) = HouseholdView(view.snapshot, channels, view.curated)

    /** The rules [mutated] breaks, with [case] and the planner's [picks]. */
    fun broken(
        mutated: HouseholdView,
        case: GateCase = this.case,
        picks: List<String> = emptyList(),
    ): Set<Rule> = GateInvariants.check("mutation", mutated, case, picks).map { it.rule }.toSet()

    /** A title of the synthetic adult slice, by its label. */
    fun slice(title: String): ScheduleItem =
        SyntheticAdultSlice.ALL
            .first { it.title == title }
            .entry.item

    /** The first episode of the first series of the fixture that [match]es. */
    fun episodeOf(match: (RuleSeries) -> Boolean): ScheduleItem {
        val series =
            view.snapshot.series.values
                .first(match)
        return view.snapshot.items
            .first { it.item.seriesId == series.id }
            .item
    }

    fun GateChannel.empty() = copy(items = emptyList(), homed = emptySet())
}
