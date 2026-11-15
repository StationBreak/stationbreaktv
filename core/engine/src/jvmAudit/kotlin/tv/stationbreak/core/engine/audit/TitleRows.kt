// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.curation.Lane
import tv.stationbreak.core.engine.lineup.curation.Titles
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot

/** One candidate place for a title: a lane of the on-set, the candidate's position in it, and its score there. */
private class Place(
    val lane: Lane,
    val position: Int,
    val score: Long,
)

/**
 * The row of every title of the snapshot: its home and runner-up over the channels that are on (each with its score by
 * term), where else it airs, or why it has no home. The home is the curation's own ([OnAir.homeOf]); the runner-up
 * is read from the same lanes by the curation's rule: the best fit among the candidate channels, guest-only ones last
 * while any other fits, ties to the lower number.
 */
internal class TitleRows(
    private val snapshot: RuleSnapshot,
    private val lanes: AuditLanes,
    private val onAir: OnAir,
) {
    private val onIds: Set<ChannelId> = onAir.curatedChannels.mapTo(HashSet()) { it.id }
    private val causes = TitleCauses(snapshot, lanes)
    private val placesOf = HashMap<CanonicalId, MutableList<Place>>()
    private val shelvedOf = HashMap<CanonicalId, MutableList<String>>()
    private val airsOn = HashMap<CanonicalId, MutableList<String>>()

    init {
        for (lane in lanes.lanes) {
            val here = lane.channel.id in onIds
            for (p in lane.cand.indices) {
                val title = lanes.titleOf(lane, p).id
                if (here) {
                    placesOf.getOrPut(title) { ArrayList() } += Place(lane, p, lane.score[p])
                } else {
                    val channel = lanes.channelOf(lane)
                    val why = channel.shelfReason?.name ?: "NotOn"
                    shelvedOf.getOrPut(title) { ArrayList() } += "${lane.channel.recipe.key} ($why)"
                }
            }
        }
        for (channel in onAir.channels) for (t in channel.titles) airsOn.getOrPut(t) { ArrayList() } += channel.key
    }

    /** The channels (keys) that air [title], in the order [OnAir.channels] lists them. */
    fun airsOn(title: CanonicalId): List<String> = airsOn[title].orEmpty()

    fun rows(): List<TitleRow> {
        val titles = Titles.of(snapshot, snapshot.items.map { it.item })
        val items = HashMap<CanonicalId, Int>()
        val runtime = HashMap<CanonicalId, Long>()
        for (entry in snapshot.items) {
            val group = entry.item.groupId
            items.merge(group, 1, Int::plus)
            runtime.merge(group, entry.item.runtimeMs, Long::plus)
        }
        return titles.keys.sortedBy { it.value }.map { id ->
            row(id, titles.getValue(id).isSeries, items.getValue(id), runtime.getValue(id))
        }
    }

    // A series by its own name (none: the snapshot holds no row for it), a film or a lone episode by its item's.
    private val names: Map<CanonicalId, String?> by lazy {
        val out = HashMap<CanonicalId, String?>()
        for (entry in snapshot.items) if (entry.item.seriesId == null) out[entry.item.id] = entry.name
        for ((id, series) in snapshot.series) out[id] = series.name
        out
    }

    private fun row(
        id: CanonicalId,
        series: Boolean,
        items: Int,
        runtimeMs: Long,
    ): TitleRow {
        val places = ranked(placesOf[id].orEmpty())
        val homeId = onAir.homeOf(id)
        val home = places.firstOrNull()?.let(::scored)
        check(
            home == null || homeId ==
                places
                    .first()
                    .lane.channel.id,
        ) { "The curation's home is the best fit" }
        val reach = if (homeId == null) causes.reachOf(id) else null
        val aired = airsOn(id)
        val homeKey = home?.channel
        return TitleRow(
            id = id.value,
            name = names[id],
            series = series,
            items = items,
            runtimeMs = runtimeMs,
            home = home,
            runnerUp = places.getOrNull(1)?.let(::scored),
            guestSpots = aired.filter { it != homeKey && !it.startsWith("leftovers:") },
            cause = reach?.cause,
            // For a title that is a candidate somewhere, the shelved lanes say it; "closest" is for one that is not.
            closest = if (reach?.cause == Cause.OnlyOnShelvedChannels) emptyList() else reach?.closest.orEmpty(),
            shelvedLanes = if (homeId == null) shelvedOf[id].orEmpty() else emptyList(),
            fallback = if (homeId == null) aired.filter { it.startsWith("leftovers:") } else emptyList(),
        )
    }

    // The best fit first; a guest-only channel only after every other (the curation leaves them out while any fits).
    private fun ranked(places: List<Place>): List<Place> {
        val regular = places.filter { !it.lane.guestOnly }
        val guestOnly = places.filter { it.lane.guestOnly }
        // Lanes are in channel order and the sort is stable: equal scores keep the lower number first.
        val byScore = compareByDescending<Place> { it.score }
        return regular.sortedWith(byScore) + guestOnly.sortedWith(byScore)
    }

    private fun scored(place: Place): ChannelScore {
        val terms = lanes.termsOf(place.lane, place.position)
        return ChannelScore(place.lane.channel.recipe.key, terms)
    }
}
