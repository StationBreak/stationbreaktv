// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot

/** The starters chosen to be on, and why each other candidate is on the shelf. */
class Selected(
    val on: Set<ChannelId>,
    val shelf: Map<ChannelId, ShelfReason>,
)

/**
 * Chooses the starter channels to switch on by identity (spec 23, A10, Resolution 28): each round re-homes every
 * title over all candidates but the dropped ones, scores the channels that qualify, takes the top room, then re-homes
 * over the chosen set and drops any chosen channel that no longer qualifies. At most [CurationDefaults.ROUNDS] rounds;
 * after the last, the set stands. Integer arithmetic only; ties go to the lower channel number, so input order never
 * matters.
 */
object Selection {
    /**
     * [candidates] are the channels in play (their pools under their own policy); [pinned] are on whatever their
     * stats and never dropped; [optIn] channels are on only when pinned, and are left out of the rounds otherwise, so
     * they take no titles. [room] is how many unpinned channels may be on.
     */
    fun select(
        snapshot: RuleSnapshot,
        candidates: List<CurationChannel>,
        pinned: Set<ChannelId>,
        optIn: Set<ChannelId>,
        room: Int,
    ): Selected = select(snapshot, candidates, pinned, optIn, room, Curation::homes)

    @Suppress("LongParameterList") // The public five plus the homing, which a test replaces.
    internal fun select(
        snapshot: RuleSnapshot,
        candidates: List<CurationChannel>,
        pinned: Set<ChannelId>,
        optIn: Set<ChannelId>,
        room: Int,
        homesOf: (RuleSnapshot, List<CurationChannel>) -> Homes,
    ): Selected {
        val places = room.coerceAtLeast(0)
        var all = candidates.filter { it.id in pinned || it.id !in optIn }
        var on: Set<ChannelId> = pinned
        var scores: Map<ChannelId, Long> = emptyMap()
        var rounds = 0
        var settled = false
        while (!settled && rounds < CurationDefaults.ROUNDS) {
            rounds++
            val homes = homesOf(snapshot, all)
            scores = scoresOf(all, pinned, homes)
            on = pinned + top(all, scores, places)
            val chosen = all.filter { it.id in on }
            // Everything chosen means the same channels as the first homing: no need to work it out again.
            val homesOn = if (chosen.size == all.size) homes else homesOf(snapshot, chosen)
            val bad = HashSet<ChannelId>()
            for (c in chosen) if (c.id !in pinned && !homesOn.stats(c.id).qualifies) bad.add(c.id)
            if (bad.isNotEmpty()) all = all.filter { it.id !in bad }
            settled = bad.isEmpty()
        }
        return Selected(on, shelfOf(candidates, on, optIn, scores))
    }

    /** min(capped, 80 h) in 36 s units (a hundredth of an hour), 1500 per anchor to 4, 100 per title to 30. */
    internal fun channelScore(s: ChannelStats): Long =
        minOf(s.cappedMs, CurationDefaults.SCORE_HOURS_CAP_MS) / SCORE_MS_UNIT +
            ANCHOR_POINTS * minOf(s.anchors, MAX_ANCHORS) +
            TITLE_POINTS * minOf(s.titles, MAX_TITLES)

    private fun scoresOf(
        all: List<CurationChannel>,
        pinned: Set<ChannelId>,
        homes: Homes,
    ): Map<ChannelId, Long> {
        val scores = HashMap<ChannelId, Long>()
        for (c in all) {
            if (c.id in pinned) continue
            val stats = homes.stats(c.id)
            if (stats.qualifies) scores[c.id] = channelScore(stats)
        }
        return scores
    }

    // The best [places] scored channels: score high to low, then number low to high, then ID, so the order is total.
    private fun top(
        all: List<CurationChannel>,
        scores: Map<ChannelId, Long>,
        places: Int,
    ): List<ChannelId> {
        val scored = all.filter { it.id in scores }
        val ordered =
            scored.sortedWith { a, b ->
                val byScore = scores.getValue(b.id).compareTo(scores.getValue(a.id))
                when {
                    byScore != 0 -> byScore
                    a.number != b.number -> a.number.compareTo(b.number)
                    else -> a.id.value.compareTo(b.id.value)
                }
            }
        return ordered.take(places).map { it.id }
    }

    // Off the lineup: opt-in, else nothing fits at all, else it qualified but missed the room, else too thin.
    private fun shelfOf(
        candidates: List<CurationChannel>,
        on: Set<ChannelId>,
        optIn: Set<ChannelId>,
        scores: Map<ChannelId, Long>,
    ): Map<ChannelId, ShelfReason> {
        val shelf = LinkedHashMap<ChannelId, ShelfReason>()
        for (c in candidates) {
            if (c.id in on) continue
            shelf[c.id] =
                when {
                    c.id in optIn -> ShelfReason.OptIn
                    c.pool.isEmpty() -> ShelfReason.Empty
                    c.id in scores -> ShelfReason.BelowCap
                    else -> ShelfReason.TooThin
                }
        }
        // An opt-in starter the caller did not pass as a candidate is still on the shelf for being opt-in.
        for (id in optIn) if (id !in on && id !in shelf) shelf[id] = ShelfReason.OptIn
        return shelf
    }

    private const val SCORE_MS_UNIT = 36_000L
    private const val ANCHOR_POINTS = 1_500L
    private const val MAX_ANCHORS = 4
    private const val TITLE_POINTS = 100L
    private const val MAX_TITLES = 30
}
