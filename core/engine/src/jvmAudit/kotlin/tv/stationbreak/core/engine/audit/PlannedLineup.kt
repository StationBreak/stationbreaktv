// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDay
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Planning
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot

/**
 * The lineup the real planner makes of a library ([LineupPlanner.plan], the shipped catalog), then the viewer's
 * choices on top: the custom channels go in as kept channels, as a reset keeps them, and a recipe the viewer switched
 * on is on and chosen by the viewer, planned again as the refresh at the next boundary does it. [day] is the day that
 * starts at that boundary: the one the report describes.
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
        val viewers = channel.chosenByViewer || channel.id.value.startsWith("custom:")
        return if (viewers) "viewer" else "planner"
    }

    companion object {
        private val SERVER = CanonicalId.parse("00000000000000000000000000000001")
        private val USER = CanonicalId.parse("00000000000000000000000000000002")
        private const val HOUR_MS = 3_600_000L
        private const val DAY_START_HOUR = 6L
        private const val FIRST_CUSTOM_NUMBER = 600

        fun of(
            snapshot: RuleSnapshot,
            names: CollectionNames,
            viewer: Viewer?,
        ): PlannedLineup {
            val notes = ArrayList<String>()
            val catalog = StarterCatalog
            val now = snapshot.cutoffMs
            val boundary = nextBoundary(now)
            val waited = !snapshot.collectionsSynced
            if (waited) notes += "The collection pass is not complete in this snapshot: planned without waiting for it."
            val kept = customChannels(viewer, now)
            var lineup =
                LineupPlanner.plan(null, SERVER, USER, 0, snapshot, names, now, boundary, catalog, waited, kept)
            if (!lineup.finalised) notes += "Not finalised: fewer than 5 recipes fill, so nothing is on."
            val chosen = viewer?.switchedOn.orEmpty()
            if (chosen.isNotEmpty() && lineup.finalised) {
                lineup = switchedOn(lineup, chosen, now, notes)
                lineup = LineupPlanner.plan(lineup, SERVER, USER, 0, snapshot, names, now, boundary, catalog, waited)
            }
            val planning = Planning(SERVER, 0, snapshot, names, catalog, now, boundary, lineup.householdFor(boundary))
            val shown = if (lineup.finalised) lineup else withEveryStarter(lineup, planning)
            val day = LineupDay.startingAt(boundary)
            return PlannedLineup(snapshot, names, shown, planning, day, notes, lineup.finalised)
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

        // The first broadcast-day start after [now] at offset 0: the planner's next boundary.
        private fun nextBoundary(now: Long): Long {
            val dayMs = RecipeResolver.DAY_MS
            val start = DAY_START_HOUR * HOUR_MS
            return ((now - start).floorDiv(dayMs) + 1) * dayMs + start
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
                    chosenByViewer = true,
                )
            }

        private fun switchedOn(
            lineup: Lineup,
            keys: List<String>,
            now: Long,
            notes: MutableList<String>,
        ): Lineup {
            val wanted = keys.toSet()
            val known = HashSet<String>()
            val channels =
                lineup.channels.map { channel ->
                    val key = (channel.source as? ChannelSource.Starter)?.recipeKey
                    if (key == null || key !in wanted) return@map channel
                    known += key
                    if (channel.number == null) {
                        notes += "The recipe $key has no number, so it cannot be switched on."
                        channel
                    } else {
                        channel.copy(
                            state = ChannelState.On,
                            chosenByViewer = true,
                            onSinceMs = now,
                            leavesAtMs = null,
                            shelfReason = null,
                        )
                    }
                }
            for (key in keys) {
                if (key !in
                    known
                ) {
                    notes += "The viewer's switch-on names no starter of the catalog (${key.length} characters)."
                }
            }
            return lineup.copy(channels = channels)
        }
    }
}
