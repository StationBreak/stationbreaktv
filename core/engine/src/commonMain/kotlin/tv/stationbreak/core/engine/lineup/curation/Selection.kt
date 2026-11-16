// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot

/**
 * The starters chosen to be on, and why each other candidate is on the shelf. Both are in channel-number order (ties
 * by ID), pinned channels that were not candidates and opt-in IDs that were not candidates last, in ID order.
 */
internal class Selected(
    val on: Set<ChannelId>,
    val shelf: Map<ChannelId, ShelfReason>,
)

/**
 * Chooses the starter channels to switch on by identity (spec 23, A10, Resolution 28): each round re-homes every
 * title over all candidates but the dropped ones, scores the channels that qualify (the pinned ones too), orders them
 * all as one (score, then channel order), takes the top room, then re-homes over the chosen set and drops any chosen
 * channel that no longer qualifies. A pinned channel inside the top room uses one of its places; one outside it, or
 * with no score, is on and costs none, so the free places are the room less the pinned channels inside the top. An
 * unranked pinned channel (one the viewer added, or an opt-in one) is homed with the rest but never ranked, so it
 * never uses a place and the free places are not reduced for it. At most [CurationDefaults.ROUNDS] rounds;
 * after the last, the set stands. Integer arithmetic only; ties go to the lower channel number, so input order never
 * matters.
 *
 * The drop never happens with the real homing, so the rounds do not repeat: removing channels only adds homes. A
 * channel's lane (its candidates, hours, anchors and scores) does not depend on the other channels, and a title's home
 * is its best-fitting candidate channel, ties to the lower number. Over a subset that still holds a title's old home,
 * that home is still the best; where the old home is gone, the title moves to another channel of the subset, which can
 * only gain. A kid or guest-only channel counts every candidate whatever the homes. So a chosen channel's titles,
 * hours and anchors never fall, and it still qualifies. The loop stays as a safety net, and a property test holds the
 * claim over generated libraries.
 */
internal object Selection {
    /**
     * [candidates] are the channels in play (their pools under their own policy); [pinned] are on whatever their
     * stats and never dropped, and are ranked with the rest: one in the top [room] of the order uses a place, one
     * outside it (or unscored) costs none. [optIn] channels are on only when pinned, and are left out of the rounds
     * otherwise, so they take no titles; a pinned one takes part in the homing but never in the ranking, so it always
     * costs no place. [room] is how many places the top has, the planner's picks (`LineupDefaults.PLANNER_PICKS`).
     * [unranked] are pinned channels that are never ranked, so they use no place wherever their score falls: the
     * starters the viewer switched on that the planner had not picked ("added"). They take part in the homing like any
     * pinned channel; unlike [optIn] they do not leave the rounds when unpinned and give no OptIn shelf reason, so a
     * pinned opt-in channel's ID in either set gives the same selection.
     */
    @Suppress("LongParameterList") // The five and the unranked set.
    fun select(
        snapshot: RuleSnapshot,
        candidates: List<CurationChannel>,
        pinned: Set<ChannelId>,
        optIn: Set<ChannelId>,
        room: Int,
        unranked: Set<ChannelId> = emptySet(),
    ): Selected = select(snapshot, candidates, pinned, optIn, room, unranked, Curation::homing)

    // The homing is a parameter for a test to replace.
    @Suppress("LongParameterList") // The public six plus the homing, which a test replaces.
    internal fun select(
        snapshot: RuleSnapshot,
        candidates: List<CurationChannel>,
        pinned: Set<ChannelId>,
        optIn: Set<ChannelId>,
        room: Int,
        unranked: Set<ChannelId> = emptySet(),
        homingOf: (RuleSnapshot, List<CurationChannel>) -> Homing,
    ): Selected {
        val places = room.coerceAtLeast(0)
        var all = candidates.filter { it.id in pinned || it.id !in optIn }
        // The lanes are built once: every later homing is of a subset of these channels.
        val homing = homingOf(snapshot, all)
        var on: Set<ChannelId> = pinned
        var scores: Map<ChannelId, Long> = emptyMap()
        var first: Homes? = null
        var rounds = 0
        var settled = false
        while (!settled && rounds < CurationDefaults.ROUNDS) {
            rounds++
            val homes = homing.homes(idsOf(all))
            if (first == null) first = homes
            scores = scoresOf(all, homes)
            on = pinned + top(all, scores, pinned, optIn + unranked, places)
            val chosen = all.filter { it.id in on }
            // Everything chosen means the same channels as the first homing: no need to work it out again.
            val homesOn = if (chosen.size == all.size) homes else homing.homes(idsOf(chosen))
            val bad = HashSet<ChannelId>()
            for (c in chosen) if (c.id !in pinned && !homesOn.stats(c.id).qualifies) bad.add(c.id)
            if (bad.isNotEmpty()) all = all.filter { it.id !in bad }
            settled = bad.isEmpty()
        }
        val order = candidates.sortedWith(CHANNEL_ORDER)
        return Selected(onInOrder(order, on), shelfOf(order, on, optIn, scores, first))
    }

    /** min(capped, 80 h) in 36 s units (a hundredth of an hour), 1500 per anchor to 4, 100 per title to 30. */
    internal fun channelScore(s: ChannelStats): Long =
        minOf(s.cappedMs, CurationDefaults.SCORE_HOURS_CAP_MS) / SCORE_MS_UNIT +
            CurationDefaults.SCORE_ANCHOR_POINTS * minOf(s.anchors, CurationDefaults.SCORE_ANCHOR_CAP) +
            CurationDefaults.SCORE_TITLE_POINTS * minOf(s.titles, CurationDefaults.SCORE_TITLE_CAP)

    private fun idsOf(channels: List<CurationChannel>): Set<ChannelId> {
        val ids = HashSet<ChannelId>(channels.size * 2)
        for (c in channels) ids.add(c.id)
        return ids
    }

    private fun scoresOf(
        all: List<CurationChannel>,
        homes: Homes,
    ): Map<ChannelId, Long> {
        val scores = HashMap<ChannelId, Long>()
        for (c in all) {
            val stats = homes.stats(c.id)
            if (stats.qualifies) scores[c.id] = channelScore(stats)
        }
        return scores
    }

    // The free channels in the top [places] of one order of every ranked channel, the pinned ones among them: score
    // high to low, then number low to high, then ID, so the order is total. A pinned channel in the top holds its
    // place there, so the free ones that fit around it are the best free ones. An opt-in or unranked channel is never
    // ranked.
    private fun top(
        all: List<CurationChannel>,
        scores: Map<ChannelId, Long>,
        pinned: Set<ChannelId>,
        optIn: Set<ChannelId>,
        places: Int,
    ): List<ChannelId> {
        val scored = all.filter { it.id in scores && it.id !in optIn }
        val ordered =
            scored.sortedWith { a, b ->
                val byScore = scores.getValue(b.id).compareTo(scores.getValue(a.id))
                if (byScore != 0) byScore else CHANNEL_ORDER.compare(a, b)
            }
        return ordered.take(places).map { it.id }.filter { it !in pinned }
    }

    private fun onInOrder(
        order: List<CurationChannel>,
        on: Set<ChannelId>,
    ): Set<ChannelId> {
        val out = LinkedHashSet<ChannelId>()
        for (c in order) if (c.id in on) out.add(c.id)
        for (id in on.sortedBy { it.value }) out.add(id)
        return out
    }

    // Off the lineup: opt-in, else no candidate title at all, else it qualified but missed the room, else too thin.
    private fun shelfOf(
        order: List<CurationChannel>,
        on: Set<ChannelId>,
        optIn: Set<ChannelId>,
        scores: Map<ChannelId, Long>,
        first: Homes?,
    ): Map<ChannelId, ShelfReason> {
        val shelf = LinkedHashMap<ChannelId, ShelfReason>()
        for (c in order) {
            if (c.id in on) continue
            shelf[c.id] =
                when {
                    c.id in optIn -> ShelfReason.OptIn
                    first == null || first.candidates(c.id) == 0 -> ShelfReason.Empty
                    c.id in scores -> ShelfReason.BelowCap
                    else -> ShelfReason.TooThin
                }
        }
        // An opt-in starter the caller did not pass as a candidate is still on the shelf for being opt-in.
        for (id in optIn.sortedBy { it.value }) if (id !in on && id !in shelf) shelf[id] = ShelfReason.OptIn
        return shelf
    }

    private const val SCORE_MS_UNIT = 36_000L
}
