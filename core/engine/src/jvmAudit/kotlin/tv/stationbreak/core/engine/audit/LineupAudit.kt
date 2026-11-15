// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.PlannerSelection
import tv.stationbreak.core.engine.lineup.curation.ChannelStats
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.Selection
import tv.stationbreak.core.engine.lineup.isOnAirOn
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot

/**
 * The lineup audit: the real planner and curation run on a library snapshot, and explained (spec: docs/design,
 * lineup-seats-and-gaps, section 2). It runs [tv.stationbreak.core.engine.lineup.LineupPlanner.plan] the way the app
 * does (the snapshot after the extras filter, the shipped catalog) and homes what the planner's curation homes.
 *
 * Scope: the planner's view. It does not apply the rating ceilings or the late-on pools of a built day
 * (`LineupDays.curationChannelOf`). What a day airs is out of scope, and the report says so.
 */
internal object LineupAudit {
    const val SCOPE =
        "The planner's view: LineupPlanner.plan and the curation, as the planner runs them, on the snapshot after " +
            "the extras filter. Not a built day: no rating ceilings and no late-on pools " +
            "(LineupDays.curationChannelOf). What a day airs is out of scope."

    fun run(input: AuditInput): AuditReport {
        val filtered = ExtrasFilter.filter(input.snapshot)
        val snapshot = filtered.snapshot
        val names = collectionNamesOf(snapshot)
        val planned = PlannedLineup.of(snapshot, names, input.viewer)
        val lanes = AuditLanes(planned)
        val onAir = OnAir(lanes)
        val titleRows = TitleRows(snapshot, lanes, onAir)
        val titles = titleRows.rows()
        return AuditReport(
            scope = SCOPE,
            catalogVersion = planned.catalog.version,
            curationVersion = Curation.VERSION_NAME,
            extrasVersion = ExtrasFilter.VERSION_NAME,
            library = summary(snapshot, titles, filtered.dropped.mapKeys { it.key.name }),
            notes = planned.notes,
            starters = starters(planned, lanes, onAir),
            titles = titles,
            overlaps = overlaps(onAir),
            totals = totals(titles, titleRows, onAir),
        )
    }

    // The names of the collections, as a day build and the planner read them (`collectionNamesOf` in core:domain).
    private fun collectionNamesOf(snapshot: RuleSnapshot): CollectionNames {
        val names = HashMap<CanonicalId, String>()
        for ((id, collection) in snapshot.collections) collection.name?.let { names[id] = it }
        return CollectionNames(names)
    }

    private fun summary(
        snapshot: RuleSnapshot,
        titles: List<TitleRow>,
        dropped: Map<String, Int>,
    ): LibrarySummary {
        val series = titles.count { it.series }
        return LibrarySummary(snapshot.items.size, titles.size, series, titles.size - series, dropped)
    }

    private fun starters(
        planned: PlannedLineup,
        lanes: AuditLanes,
        onAir: OnAir,
    ): List<StarterRow> {
        val onSet = onAir.curatedChannels.mapTo(HashSet()) { it.id }
        val candidates = lanes.channels.filter { isCandidate(planned, it.id) }.mapTo(HashSet()) { it.id }
        val selection = lanes.homes(candidates)
        val aired = lanes.homes(onSet)
        return lanes.lanes.map { lane ->
            val channel = lanes.channelOf(lane)
            val id = lane.channel.id
            val on = channel.isOnAirOn(planned.day)
            val picked = if (id in candidates) selection.stats(id) else null
            StarterRow(
                key = lane.channel.recipe.key,
                number = channel.number,
                name = lane.channel.recipe.name,
                on = on,
                by = if (on) planned.by(channel) else null,
                shelfReason = reasonOf(planned, channel, on, picked, lane.cand.size),
                kid = lane.kid,
                optIn = lane.channel.recipe.optIn,
                candidates = lane.cand.size,
                selection = if (id in candidates) stats(selection.stats(id), true) else null,
                onAir = if (id in onSet) stats(aired.stats(id), false) else null,
            )
        }
    }

    // The planner's reason for a starter that is not on, or, with nothing on yet, what the homing says of it.
    private fun reasonOf(
        planned: PlannedLineup,
        channel: LineupChannel,
        on: Boolean,
        stats: ChannelStats?,
        candidates: Int,
    ): String? =
        when {
            on && channel.leavesAtMs == null -> null
            planned.finalised -> channel.shelfReason?.name
            candidates == 0 -> "Empty"
            stats == null -> "OptIn"
            !stats.qualifies -> "TooThin"
            else -> "NotFinalised"
        }

    // What the planner's selection treats as a candidate (PlannerSelection.isCandidate).
    private fun isCandidate(
        planned: PlannedLineup,
        id: ChannelId,
    ): Boolean = PlannerSelection.isCandidate(planned.lineup.channels.first { it.id == id }, planned.planning)

    private fun stats(
        s: ChannelStats,
        scored: Boolean,
    ): StarterStats {
        val score = if (scored) Selection.channelScore(s) else null
        return StarterStats(s.titles, s.totalMs, s.cappedMs, s.anchors, s.qualifies, score)
    }

    private fun overlaps(onAir: OnAir): List<OverlapRow> {
        val out = ArrayList<OverlapRow>()
        val on = onAir.channels.filter { !it.leftovers }
        val runtime = onAir.runtimeOfItems
        for (i in on.indices) {
            for (j in i + 1 until on.size) {
                val shared = on[i].items.intersect(on[j].items)
                if (shared.isEmpty()) continue
                val titles = on[i].titles.intersect(on[j].titles).size
                out += OverlapRow(on[i].key, on[j].key, titles, shared.sumOf { runtime.getValue(it) })
            }
        }
        return out.sortedWith(compareByDescending<OverlapRow> { it.runtimeMs }.thenBy { it.a }.thenBy { it.b })
    }

    private fun totals(
        titles: List<TitleRow>,
        rows: TitleRows,
        onAir: OnAir,
    ): Totals {
        val noHome = titles.mapNotNull { if (it.home == null) it.cause else null }.groupingBy { it }.eachCount()
        val perChannel = HashMap<Int, Int>()
        for (t in titles) perChannel.merge(rows.airsOn(CanonicalId.parse(t.id)).size, 1, Int::plus)
        return Totals(
            titles = titles.size,
            homed = titles.count { it.home != null },
            noHomeByCause = Cause.entries.filter { it in noHome }.associateWith { noHome.getValue(it) },
            titlesPerChannel = onAir.channels.map { ChannelTitles(it.key, it.titles.size) },
            channelsPerTitle = perChannel.toSortedMap(),
        )
    }
}
