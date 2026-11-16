// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDay
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Planning
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.engine.lineup.startsAfter
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot

/**
 * The lineup the real planner makes of a library ([LineupPlanner.plan], the shipped catalog), then the viewer's
 * choices on top: the custom channels go in as kept channels, as a reset keeps them, and a recipe the viewer switched
 * on goes through the real edit ([LineupEdits.apply]), so it is on and marked as an addition, a renumber or an undo
 * exactly as it is in the app, planned again as the refresh at the next boundary does it. [day] is the day that
 * starts at that boundary: the one the report describes (for a viewer that hides picks and switches them on again,
 * the boundary after the first).
 */
@Suppress("LongParameterList") // A plain carrier: what the planner made, and what the audit reads of it.
internal class PlannedLineup(
    val snapshot: RuleSnapshot,
    val names: CollectionNames,
    val lineup: Lineup,
    val planning: Planning,
    val day: LineupDay,
    val notes: List<String>,
    /** False when fewer than 5 recipes fill in the first choice: [lineup] is then every starter, on the shelf. */
    val finalised: Boolean,
) {
    val catalog: StarterCatalogView get() = planning.catalog

    /** Whether the planner or the viewer switched [channel] on, for a channel that is on the air on [day]. */
    fun by(channel: LineupChannel): String {
        val viewers = channel.chosenByViewer || channel.source is ChannelSource.Custom
        return if (viewers) "viewer" else "planner"
    }

    companion object {
        private val SERVER = CanonicalId.parse("00000000000000000000000000000001")
        private val USER = CanonicalId.parse("00000000000000000000000000000002")
        private const val FIRST_CUSTOM_NUMBER = 600

        // An hour into the day after a boundary: past it, with the roll done, where a switch-on is an addition.
        private const val NEXT_DAY_OFFSET_MS = 3_600_000L

        fun of(
            snapshot: RuleSnapshot,
            names: CollectionNames,
            viewer: Viewer?,
        ): PlannedLineup {
            val notes = ArrayList<String>()
            val catalog = StarterCatalog
            val now = snapshot.cutoffMs
            val waited = !snapshot.collectionsSynced
            if (waited) notes += "The collection pass is not complete in this snapshot: planned without waiting for it."
            val run = Run(snapshot, names, waited, notes)
            var at = Moment(now, nextBoundary(now))
            var lineup = run.firstChoice(customChannels(viewer, now), at)
            if (!lineup.finalised) notes += "Not finalised: fewer than 5 recipes fill, so nothing is on."
            if (viewer != null && lineup.finalised) {
                val chosen = run.viewerChoices(lineup, viewer, at)
                lineup = chosen.first
                at = chosen.second
            }
            val household = lineup.householdFor(at.boundary)
            val planning = Planning(SERVER, 0, snapshot, names, catalog, at.now, at.boundary, household)
            val shown = if (lineup.finalised) lineup else withEveryStarter(lineup, planning)
            val day = LineupDay.startingAt(at.boundary)
            return PlannedLineup(snapshot, names, shown, planning, day, notes, lineup.finalised)
        }

        // A time and the broadcast-day start after it: what an edit or a plan is made at.
        private class Moment(
            val now: Long,
            val boundary: Long,
        )

        // The plans and edits of one audit: the real planner and the real edits, on the one library.
        private class Run(
            val snapshot: RuleSnapshot,
            val names: CollectionNames,
            val waited: Boolean,
            val notes: MutableList<String>,
        ) {
            fun firstChoice(
                kept: List<LineupChannel>,
                at: Moment,
            ): Lineup =
                LineupPlanner.plan(
                    null,
                    SERVER,
                    USER,
                    0,
                    snapshot,
                    names,
                    at.now,
                    at.boundary,
                    StarterCatalog,
                    waited,
                    kept,
                )

            fun plan(
                lineup: Lineup,
                at: Moment,
            ): Lineup =
                LineupPlanner.plan(
                    lineup,
                    SERVER,
                    USER,
                    0,
                    snapshot,
                    names,
                    at.now,
                    at.boundary,
                    StarterCatalog,
                    waited,
                )

            // The viewer's choices on the lineup of the first choice, and the moment the report describes. One that
            // needs a second day (hidden picks, kept starters) lives the first one through, planner and boundary
            // included, and switches on after it; the others switch on at once.
            fun viewerChoices(
                lineup: Lineup,
                viewer: Viewer,
                at: Moment,
            ): Pair<Lineup, Moment> {
                var current = lineup
                var moment = at
                val ons = ArrayList<ChannelId>()
                if (viewer.needsSecondDay) {
                    val first = dayOne(current, viewer, moment)
                    val next = moment.boundary + NEXT_DAY_OFFSET_MS
                    current = first.first.rolled(next)
                    moment = Moment(next, nextBoundary(next))
                    ons += viewer.hiddenThenOn.map { LineupIds.starter(it) }
                    ons += first.second
                }
                val known = current.channels.mapNotNull { (it.source as? ChannelSource.Starter)?.recipeKey }.toSet()
                for (key in viewer.switchedOn) {
                    if (key in known) {
                        ons += LineupIds.starter(key)
                    } else {
                        notes += "The viewer's switch-on names no starter of the catalog (${key.length} characters)."
                    }
                }
                if (ons.isEmpty()) return current to moment
                for (id in ons) current = edited(current, LineupEdit.SwitchOn(id, confirmedOptIn = true), moment)
                return plan(current, moment) to moment
            }

            // Day one: the picks to hide go off; the planner plans again and puts the next picks in their places, to
            // start at the boundary; the first of those the viewer then switches off before they air (the kept
            // starters); and the planner plans once more. The kept starters' IDs come back.
            private fun dayOne(
                lineup: Lineup,
                viewer: Viewer,
                at: Moment,
            ): Pair<Lineup, List<ChannelId>> {
                var current = lineup
                for (key in viewer.hiddenThenOn) {
                    current = edited(current, LineupEdit.SwitchOff(LineupIds.starter(key)), at)
                }
                current = plan(current, at)
                val today = LineupDay.endingAt(at.boundary)
                val starting =
                    current.channels
                        .filter { it.source is ChannelSource.Starter && it.state == ChannelState.On }
                        .filter { it.startsAfter(today) }
                        .sortedBy { it.number }
                        .take(viewer.keptThenOn)
                        .map { it.id }
                for (id in starting) current = edited(current, LineupEdit.SwitchOff(id), at)
                return plan(current, at) to starting
            }

            // The edit through the real edit code. A refusal is a note (a recipe key of the catalog only, never what
            // the viewer typed) and changes nothing.
            private fun edited(
                lineup: Lineup,
                edit: LineupEdit.ChannelEdit,
                at: Moment,
            ): Lineup =
                when (val outcome = LineupEdits.apply(lineup, edit, at.now, at.boundary, StarterCatalog)) {
                    is EditOutcome.Applied -> {
                        outcome.lineup
                    }

                    is EditOutcome.Refused -> {
                        val source = lineup.channels.firstOrNull { it.id == edit.id }?.source as? ChannelSource.Starter
                        notes += "The recipe ${source?.recipeKey} could not be switched: ${outcome.refusal}."
                        lineup
                    }
                }
        }

        // An unfinalised lineup has no starters. The audit still looks at every recipe, each on the shelf, to say where
        // the titles would go.
        private fun withEveryStarter(
            lineup: Lineup,
            planning: Planning,
        ): Lineup {
            val starters =
                planning.catalog.recipes.map {
                    planning.starterChannel(it, it.number, ChannelState.Shelf, planning.resolve(it))
                }
            return lineup.copy(channels = lineup.channels + starters)
        }

        // The first broadcast-day start after [now]: the epoch arithmetic of `LineupService.nextBoundary`
        // (`Epochs.epochStart(settings, Epochs.epochNumber(settings, now) + 1)`) on the settings a leftovers channel
        // resolves to. core:engine may not depend on core:scheduler (MODULES.md), so the two lines are written here.
        private fun nextBoundary(now: Long): Long {
            val settings = RecipeResolver.leftoversSettings(StarterCatalog.leftovers.first(), SERVER, 0)
            val epoch = (now + settings.offsetMs - settings.startMs).floorDiv(settings.epochMs)
            return (epoch + 1) * settings.epochMs + settings.startMs - settings.offsetMs
        }

        private fun customChannels(
            viewer: Viewer?,
            now: Long,
        ): List<LineupChannel> =
            viewer?.customChannels.orEmpty().mapIndexed { i, custom ->
                LineupChannel(
                    id = ChannelId("custom:${i + 1}"),
                    defaultNumber = null,
                    numberOverride = FIRST_CUSTOM_NUMBER + i,
                    nameOverride = custom.name,
                    state = ChannelState.On,
                    source = ChannelSource.Custom(custom.rules),
                    onSinceMs = now,
                    viewerChoice = ViewerChoice.Touched,
                )
            }
    }
}
