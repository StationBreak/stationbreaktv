// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.CurationTables
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleKeeps
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.rules.ChannelRules

/**
 * One channel the curation reads: its recipe, whether it is a kid channel, its resolved [rules] (household sources
 * already applied), its [settings], and its [pool], evaluated under its kid or grown-up policy. The curation only ever
 * takes items out of [pool]; it never adds one.
 */
@Suppress("LongParameterList") // A plain carrier: one field per fact the curation reads.
class CurationChannel(
    val id: ChannelId,
    val number: Int,
    val recipe: Recipe,
    val kid: Boolean,
    val rules: ChannelRules,
    val settings: SchedulerSettings,
    val pool: List<ScheduleItem>,
)

/**
 * A channel's pool after curation. [items] are its members' airable items in canonical-ID order; [claimed] is the
 * same list (trimming is the scheduler's, from [keeps], so a trimmed item is still claimed). [keeps] holds the share
 * cap in ms of every member series over it.
 */
data class CuratedPool(
    val items: List<ScheduleItem>,
    val claimed: List<ScheduleItem>,
    val weights: TitleWeights,
    val keeps: TitleKeeps,
)

/** What a channel holds after homing: its titles, their hours whole and under the share cap, and its anchors. */
data class ChannelStats(
    val titles: Int,
    val totalMs: Long,
    val cappedMs: Long,
    val anchors: Int,
    val qualifies: Boolean,
)

/** Where every title lives, and what each channel holds when homed. */
class Homes internal constructor(
    private val titleIndex: Map<CanonicalId, Int>,
    private val homeOfTitle: Array<ChannelId?>,
    private val statsOf: Map<ChannelId, ChannelStats>,
) {
    /** The home channel of [title], or null when no channel has it as a candidate. */
    fun homeOf(title: CanonicalId): ChannelId? {
        val index = titleIndex[title] ?: return null
        return homeOfTitle[index]
    }

    /** The stats of [channel], which must be one of the channels the homes were worked out over. */
    fun stats(channel: ChannelId): ChannelStats {
        val stats = statsOf[channel]
        return requireNotNull(stats) { "The channel was not one of the curated channels" }
    }
}

/**
 * The cross-channel curation step (spec 23, `curation-1`): one home per title by an integer fit score, guests on
 * grown-up channels under a 30 % share, the 25 % share cap as per-series keeps, and weights 3/2/1. Integer arithmetic
 * only, and every result is in channel-number or canonical-ID order, so any input order gives the same answer.
 */
object Curation {
    const val VERSION_NAME: String = CurationTables.VERSION_NAME

    /** Homes every candidate title onto [channels] and gives each channel's stats. */
    fun homes(
        snapshot: RuleSnapshot,
        channels: List<CurationChannel>,
    ): Homes = CurationRun(snapshot, channels).homes()

    /** The curated pools of the switched-on channels [on]: homes over [on], then guests, keeps and weights. */
    fun pools(
        snapshot: RuleSnapshot,
        on: List<CurationChannel>,
    ): Map<ChannelId, CuratedPool> = CurationRun(snapshot, on).pools()
}

// A guest's packed sort key keeps its index in the low bits: far more than any channel's candidates.
private const val GUEST_BITS = 24
private const val GUEST_MASK = (1L shl GUEST_BITS) - 1

private val CHANNEL_ORDER =
    Comparator<CurationChannel> { a, b ->
        val byNumber = a.number.compareTo(b.number)
        if (byNumber != 0) byNumber else a.id.value.compareTo(b.id.value)
    }

private fun sortedChannels(channels: List<CurationChannel>): List<CurationChannel> {
    val sorted = channels.sortedWith(CHANNEL_ORDER)
    val seen = HashSet<ChannelId>()
    for (c in sorted) require(seen.add(c.id)) { "A channel is curated once" }
    return sorted
}

/** One run of the curation over a fixed channel list: everything is worked out once, in plain loops. */
private class CurationRun(
    snapshot: RuleSnapshot,
    channels: List<CurationChannel>,
) {
    private val built = LaneBuilder(snapshot, sortedChannels(channels))
    private val lanes = built.lanes
    private val titles = built.titles

    // Candidate channels by title: entries titleStart[t] until titleStart[t + 1] give (lane, candidate position), in
    // lane order, which is channel order.
    private val titleStart = IntArray(titles.size + 1)
    private val entryLane: IntArray
    private val entryPos: IntArray
    private val homeLane = IntArray(titles.size)

    init {
        for (lane in lanes) for (t in lane.cand) titleStart[t + 1]++
        for (t in titles.indices) titleStart[t + 1] += titleStart[t]
        entryLane = IntArray(titleStart[titles.size])
        entryPos = IntArray(entryLane.size)
        val fill = titleStart.copyOf()
        for (l in lanes.indices) {
            val cand = lanes[l].cand
            for (p in cand.indices) {
                val e = fill[cand[p]]++
                entryLane[e] = l
                entryPos[e] = p
            }
        }
        for (t in titles.indices) placeHome(t)
    }

    // The best fit among the title's candidate channels, guest-only ones left out while any other fits. Ties go to the
    // lower number: lanes are in number order and only a strictly higher score replaces the best so far.
    private fun placeHome(t: Int) {
        var anyRegular = false
        for (e in titleStart[t] until titleStart[t + 1]) {
            anyRegular = anyRegular || !lanes[entryLane[e]].channel.recipe.guestOnly
        }
        var best = -1
        for (e in titleStart[t] until titleStart[t + 1]) {
            val eligible = !anyRegular || !lanes[entryLane[e]].channel.recipe.guestOnly
            if (eligible && (best < 0 || scoreAt(e) > scoreAt(best))) best = e
        }
        homeLane[t] = if (best < 0) -1 else entryLane[best]
        if (best >= 0) lanes[entryLane[best]].home[entryPos[best]] = true
    }

    private fun scoreAt(e: Int): Long = lanes[entryLane[e]].score[entryPos[e]]

    fun homes(): Homes {
        val homeIds = arrayOfNulls<ChannelId>(titles.size)
        for (t in titles.indices) if (homeLane[t] >= 0) homeIds[t] = lanes[homeLane[t]].channel.id
        val stats = LinkedHashMap<ChannelId, ChannelStats>(lanes.size * 2)
        for (lane in lanes) stats[lane.channel.id] = statsOf(lane)
        return Homes(built.titleIndex, homeIds, stats)
    }

    // members = the whole candidate list on a kid channel, the homes on a grown-up one.
    private fun statsOf(lane: Lane): ChannelStats {
        var count = 0
        var total = 0L
        var anchors = 0
        for (p in lane.cand.indices) {
            if (!lane.kid && !lane.home[p]) continue
            count++
            total += lane.ms[p]
            if (lane.anchor[p]) anchors++
        }
        val cap = CurationDefaults.shareCapMs(total)
        var capped = 0L
        for (p in lane.cand.indices) if (lane.kid || lane.home[p]) capped += minOf(lane.ms[p], cap)
        val minTitles = if (lane.kid) CurationDefaults.MIN_TITLES_KIDS else CurationDefaults.MIN_TITLES
        val qualifies = capped >= CurationDefaults.MIN_USABLE_MS && count >= minTitles
        return ChannelStats(count, total, capped, anchors, qualifies)
    }

    fun pools(): Map<ChannelId, CuratedPool> {
        placeGuests()
        for (lane in lanes) if (!lane.kid) capGuests(lane)
        val out = LinkedHashMap<ChannelId, CuratedPool>(lanes.size * 2)
        for (lane in lanes) out[lane.channel.id] = poolOf(lane)
        return out
    }

    // A title homed on a grown-up channel guests on at most two other grown-up channels where its tone is at least a
    // quarter of the channel's home hours, best fit first, ties to the lower number. A made-for-kids title never
    // guests (design §5: kid titles never guest on grown-up channels).
    private fun placeGuests() {
        val homeTone = Array(lanes.size) { LongArray(TONES) }
        val homeSum = LongArray(lanes.size)
        for (l in lanes.indices) {
            val lane = lanes[l]
            for (p in lane.cand.indices) {
                if (!lane.home[p]) continue
                homeTone[l][titles[lane.cand[p]].tone] += lane.ms[p]
                homeSum[l] += lane.ms[p]
            }
        }
        for (t in titles.indices) {
            val h = homeLane[t]
            if (h >= 0 && !lanes[h].kid && !titles[t].title.madeForKids) placeGuestsOf(t, h, homeTone, homeSum)
        }
    }

    private fun placeGuestsOf(
        t: Int,
        home: Int,
        homeTone: Array<LongArray>,
        homeSum: LongArray,
    ) {
        val tone = titles[t].tone
        var first = -1
        var second = -1
        for (e in titleStart[t] until titleStart[t + 1]) {
            val l = entryLane[e]
            val fitsTone = CurationDefaults.GUEST_TONE_DIVISOR * homeTone[l][tone] >= homeSum[l]
            if (l == home || lanes[l].kid || !fitsTone) continue
            if (first < 0 || scoreAt(e) > scoreAt(first)) {
                second = first
                first = e
            } else if (second < 0 || scoreAt(e) > scoreAt(second)) {
                second = e
            }
        }
        if (first >= 0) lanes[entryLane[first]].guest[entryPos[first]] = true
        if (second >= 0) lanes[entryLane[second]].guest[entryPos[second]] = true
    }

    // While guests take more than 3/7 of the home hours (over 30 % of the airtime), drop the weakest guest: the lowest
    // score, and among equal scores the later title ID.
    private fun capGuests(lane: Lane) {
        var homeMs = 0L
        var guestMs = 0L
        var guests = 0
        for (p in lane.cand.indices) {
            if (lane.home[p]) homeMs += lane.ms[p]
            if (lane.guest[p]) {
                guestMs += lane.ms[p]
                guests++
            }
        }
        if (guestMs * CurationDefaults.GUEST_DEN <= homeMs * CurationDefaults.GUEST_NUM) return
        check(guests <= GUEST_MASK) { "Too many guests to order" }
        val positions = IntArray(guests)
        val keys = LongArray(guests)
        var g = 0
        for (p in lane.cand.indices) {
            if (!lane.guest[p]) continue
            positions[g] = p
            // Ascending keys: the lower score first; among equal scores a later position (a later title ID) first.
            keys[g] = (lane.score[p] shl GUEST_BITS) + (GUEST_MASK - g)
            g++
        }
        keys.sort()
        var k = 0
        while (guestMs * CurationDefaults.GUEST_DEN > homeMs * CurationDefaults.GUEST_NUM) {
            val p = positions[(GUEST_MASK - (keys[k++] and GUEST_MASK)).toInt()]
            lane.guest[p] = false
            guestMs -= lane.ms[p]
        }
    }

    // items = claimed = the members' airable items in canonical-ID order. keeps: every member series over the share
    // cap of the members' hours keeps the cap (films never). weights: anchor 3, home 2, otherwise 1 (left out).
    private fun poolOf(lane: Lane): CuratedPool {
        val items = ArrayList<ScheduleItem>()
        for (i in lane.pool.indices) {
            val pos = lane.itemPos[i]
            if (pos >= 0 && lane.isMember(pos)) items.add(lane.pool[i])
        }
        var total = 0L
        for (p in lane.cand.indices) if (lane.isMember(p)) total += lane.ms[p]
        val cap = CurationDefaults.shareCapMs(total)
        val keeps = HashMap<CanonicalId, Long>()
        val weights = HashMap<CanonicalId, Int>()
        for (p in lane.cand.indices) {
            if (!lane.isMember(p)) continue
            val title = titles[lane.cand[p]].title
            if (title.isSeries && lane.ms[p] > cap) keeps[title.id] = cap
            val weight = weightOf(lane, p)
            if (weight != CurationDefaults.WEIGHT_GUEST) weights[title.id] = weight
        }
        return CuratedPool(items, items, TitleWeights.of(weights), TitleKeeps.of(keeps))
    }

    private fun weightOf(
        lane: Lane,
        p: Int,
    ): Int =
        when {
            lane.anchor[p] -> CurationDefaults.WEIGHT_ANCHOR
            lane.home[p] -> CurationDefaults.WEIGHT_HOME
            else -> CurationDefaults.WEIGHT_GUEST
        }
}
