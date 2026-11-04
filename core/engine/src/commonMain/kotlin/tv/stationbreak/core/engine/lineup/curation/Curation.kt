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
    private val candidatesOf: Map<ChannelId, Int>,
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

    /**
     * How many titles [channel] has as candidates: the titles of its airable pool that the anime rule admits. Zero
     * means nothing fits the channel at all; it does not depend on which other channels were curated with it.
     */
    fun candidates(channel: ChannelId): Int {
        val count = candidatesOf[channel]
        return requireNotNull(count) { "The channel was not one of the curated channels" }
    }
}

/**
 * The cross-channel curation step (spec 23, `curation-1`): one home per title by an integer fit score; guests on
 * grown-up channels, under a 30 % share except on guest-only (decade) channels, which are built from guests; the 25 %
 * share cap as per-series keeps; and weights 3/2/1. Title facts come from the snapshot, so the channels passed do not
 * change them. Integer arithmetic only, and every result is in channel-number or canonical-ID order, so any input
 * order gives the same answer.
 */
object Curation {
    const val VERSION_NAME: String = CurationTables.VERSION_NAME

    /** Homes every candidate title onto [channels] and gives each channel's stats. */
    fun homes(
        snapshot: RuleSnapshot,
        channels: List<CurationChannel>,
    ): Homes = CurationHoming(snapshot, channels).homes(null)

    /** A homing over [channels] that works out their lanes once and homes any subset of them. */
    internal fun homing(
        snapshot: RuleSnapshot,
        channels: List<CurationChannel>,
    ): Homing = CurationHoming(snapshot, channels)

    /** The curated pools of the switched-on channels [on]: homes over [on], then guests, keeps and weights. */
    fun pools(
        snapshot: RuleSnapshot,
        on: List<CurationChannel>,
    ): Map<ChannelId, CuratedPool> = CurationRun(LaneBuilder(snapshot, sortedChannels(on))).pools()
}

/**
 * Homes a subset of the channels it was built over. A channel's lane (candidates, hours, anchors, scores) depends only
 * on the channel and the snapshot, never on the other channels, so the lanes are built once and a subset re-runs only
 * the homing and the stats.
 */
internal interface Homing {
    /** The homes over [channels], or over every channel when null. Channels it was not built over are ignored. */
    fun homes(channels: Set<ChannelId>?): Homes
}

private class CurationHoming(
    snapshot: RuleSnapshot,
    channels: List<CurationChannel>,
) : Homing {
    private val built = LaneBuilder(snapshot, sortedChannels(channels))

    override fun homes(channels: Set<ChannelId>?): Homes {
        val lanes =
            if (channels == null) built.lanes else built.lanes.filter { it.channel.id in channels }.toTypedArray()
        return CurationRun(built, lanes).homes()
    }
}

// A guest's packed sort key keeps its index in the low bits: far more than any channel's candidates.
private const val GUEST_BITS = 24
private const val GUEST_MASK = (1L shl GUEST_BITS) - 1

internal val CHANNEL_ORDER =
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
    private val built: LaneBuilder,
    private val lanes: Array<Lane> = built.lanes,
) {
    private val titles = built.titles

    // The run's marks, parallel to each lane's candidates.
    private val home = Array(lanes.size) { BooleanArray(lanes[it].cand.size) }
    private val guest = Array(lanes.size) { BooleanArray(lanes[it].cand.size) }

    // Candidate channels by title: entries titleStart[t] until titleStart[t + 1] give (lane, candidate position), in
    // lane order, which is channel order.
    private val titleStart = IntArray(titles.size + 1)
    private val entryLane: IntArray
    private val entryPos: IntArray
    private val entryScore: LongArray
    private val homeLane = IntArray(titles.size)

    init {
        for (lane in lanes) for (t in lane.cand) titleStart[t + 1]++
        for (t in titles.indices) titleStart[t + 1] += titleStart[t]
        entryLane = IntArray(titleStart[titles.size])
        entryPos = IntArray(entryLane.size)
        entryScore = LongArray(entryLane.size)
        val fill = titleStart.copyOf()
        for (l in lanes.indices) {
            val cand = lanes[l].cand
            for (p in cand.indices) {
                val e = fill[cand[p]]++
                entryLane[e] = l
                entryPos[e] = p
                entryScore[e] = lanes[l].score[p]
            }
        }
        for (t in titles.indices) placeHome(t)
    }

    // The best fit among the title's candidate channels, guest-only ones left out while any other fits. Ties go to the
    // lower number: lanes are in number order and only a strictly higher score replaces the best so far.
    private fun placeHome(t: Int) {
        var anyRegular = false
        for (e in titleStart[t] until titleStart[t + 1]) anyRegular = anyRegular || !lanes[entryLane[e]].guestOnly
        var best = -1
        for (e in titleStart[t] until titleStart[t + 1]) {
            val eligible = !anyRegular || !lanes[entryLane[e]].guestOnly
            if (eligible && (best < 0 || entryScore[e] > entryScore[best])) best = e
        }
        homeLane[t] = if (best < 0) -1 else entryLane[best]
        if (best >= 0) home[entryLane[best]][entryPos[best]] = true
    }

    // A member airs here: every candidate of a kid channel, the homes and guests of a grown-up one.
    private fun isMember(
        l: Int,
        p: Int,
    ): Boolean = lanes[l].kid || home[l][p] || guest[l][p]

    fun homes(): Homes {
        val homeIds = arrayOfNulls<ChannelId>(titles.size)
        for (t in titles.indices) if (homeLane[t] >= 0) homeIds[t] = lanes[homeLane[t]].channel.id
        val stats = LinkedHashMap<ChannelId, ChannelStats>(lanes.size * 2)
        val candidates = HashMap<ChannelId, Int>(lanes.size * 2)
        for (l in lanes.indices) {
            stats[lanes[l].channel.id] = statsOf(l)
            candidates[lanes[l].channel.id] = lanes[l].cand.size
        }
        return Homes(built.titleIndex, homeIds, stats, candidates)
    }

    // members = the whole candidate list on a kid or guest-only channel (what it can air), the homes on any other.
    private fun statsOf(l: Int): ChannelStats {
        val lane = lanes[l]
        val all = lane.kid || lane.guestOnly
        var count = 0
        var total = 0L
        var anchors = 0
        for (p in lane.cand.indices) {
            if (!all && !home[l][p]) continue
            count++
            total += lane.ms[p]
            if (lane.anchor[p]) anchors++
        }
        val cap = CurationDefaults.shareCapMs(total)
        var capped = 0L
        for (p in lane.cand.indices) if (all || home[l][p]) capped += minOf(lane.ms[p], cap)
        val minTitles = if (lane.kid) CurationDefaults.MIN_TITLES_KIDS else CurationDefaults.MIN_TITLES
        val qualifies = capped >= CurationDefaults.MIN_USABLE_MS && count >= minTitles
        return ChannelStats(count, total, capped, anchors, qualifies)
    }

    fun pools(): Map<ChannelId, CuratedPool> {
        placeGuests()
        for (l in lanes.indices) if (!lanes[l].kid && !lanes[l].guestOnly) capGuests(l)
        val out = LinkedHashMap<ChannelId, CuratedPool>(lanes.size * 2)
        for (l in lanes.indices) out[lanes[l].channel.id] = poolOf(l)
        return out
    }

    // Any title that is not made for kids (design §5) guests on at most MAX_GUESTS other grown-up channels where its
    // tone fits, best fit first, ties to the lower number; its home may be a kid channel. See [fitsTone].
    private fun placeGuests() {
        val homeTone = Array(lanes.size) { LongArray(TONES) }
        val homeSum = LongArray(lanes.size)
        for (l in lanes.indices) {
            val lane = lanes[l]
            for (p in lane.cand.indices) {
                if (!home[l][p]) continue
                homeTone[l][titles[lane.cand[p]].tone] += lane.ms[p]
                homeSum[l] += lane.ms[p]
            }
        }
        val slots = IntArray(CurationDefaults.MAX_GUESTS)
        for (t in titles.indices) {
            if (homeLane[t] >= 0 && !titles[t].title.madeForKids) placeGuestsOf(t, slots, homeTone, homeSum)
        }
    }

    private fun placeGuestsOf(
        t: Int,
        slots: IntArray,
        homeTone: Array<LongArray>,
        homeSum: LongArray,
    ) {
        val h = homeLane[t]
        val tone = titles[t].tone
        slots.fill(-1)
        for (e in titleStart[t] until titleStart[t + 1]) {
            val l = entryLane[e]
            if (l != h && !lanes[l].kid && fitsTone(l, tone, homeTone[l], homeSum[l])) offer(slots, e, entryScore)
        }
        for (e in slots) if (e >= 0) guest[entryLane[e]][entryPos[e]] = true
    }

    // A guest-only channel is built from guests: the tone is a quarter or more of its candidate hours. Any other
    // grown-up channel needs homes, and the tone a quarter or more of its home hours; with no homes it takes no guest,
    // so it does not use up a title's guest spot.
    private fun fitsTone(
        l: Int,
        tone: Int,
        homeTone: LongArray,
        homeSum: Long,
    ): Boolean {
        val lane = lanes[l]
        return if (lane.guestOnly) {
            CurationDefaults.GUEST_TONE_DIVISOR * lane.candTone[tone] >= lane.candMs
        } else {
            homeSum > 0 && CurationDefaults.GUEST_TONE_DIVISOR * homeTone[tone] >= homeSum
        }
    }

    // While guests take more than 3/7 of the home hours (over 30 % of the airtime), drop the weakest guest: the lowest
    // score, and among equal scores the later title ID. Guest-only channels are exempt.
    private fun capGuests(l: Int) {
        val lane = lanes[l]
        var homeMs = 0L
        var guestMs = 0L
        var guests = 0
        for (p in lane.cand.indices) {
            if (home[l][p]) homeMs += lane.ms[p]
            if (guest[l][p]) {
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
            if (!guest[l][p]) continue
            positions[g] = p
            // Ascending keys: the lower score first; among equal scores a later position (a later title ID) first.
            keys[g] = (lane.score[p] shl GUEST_BITS) + (GUEST_MASK - g)
            g++
        }
        keys.sort()
        var k = 0
        while (guestMs * CurationDefaults.GUEST_DEN > homeMs * CurationDefaults.GUEST_NUM) {
            val p = positions[(GUEST_MASK - (keys[k++] and GUEST_MASK)).toInt()]
            guest[l][p] = false
            guestMs -= lane.ms[p]
        }
    }

    // items = claimed = the members' airable items in canonical-ID order. keeps: every member series over the share
    // cap of the members' hours keeps the cap (films never). weights: anchor 3, home 2, otherwise 1 (left out).
    private fun poolOf(l: Int): CuratedPool {
        val lane = lanes[l]
        val items = ArrayList<ScheduleItem>()
        for (i in lane.pool.indices) {
            val pos = lane.itemPos[i]
            if (pos >= 0 && isMember(l, pos)) items.add(lane.pool[i])
        }
        var total = 0L
        for (p in lane.cand.indices) if (isMember(l, p)) total += lane.ms[p]
        val cap = CurationDefaults.shareCapMs(total)
        val keeps = HashMap<CanonicalId, Long>()
        val weights = HashMap<CanonicalId, Int>()
        for (p in lane.cand.indices) {
            if (!isMember(l, p)) continue
            val title = titles[lane.cand[p]].title
            if (title.isSeries && lane.ms[p] > cap) keeps[title.id] = cap
            val weight = weightOf(lane.anchor[p], home[l][p])
            if (weight != CurationDefaults.WEIGHT_GUEST) weights[title.id] = weight
        }
        return CuratedPool(items, items, TitleWeights.of(weights), TitleKeeps.of(keeps))
    }
}

// Puts entry [e] into the best-first [slots] when it beats one: strictly, so the earlier (lower number) keeps a tie.
private fun offer(
    slots: IntArray,
    e: Int,
    entryScore: LongArray,
) {
    var i = 0
    while (i < slots.size && slots[i] >= 0 && entryScore[e] <= entryScore[slots[i]]) i++
    if (i == slots.size) return
    for (j in slots.size - 1 downTo i + 1) slots[j] = slots[j - 1]
    slots[i] = e
}

private fun weightOf(
    anchor: Boolean,
    home: Boolean,
): Int =
    when {
        anchor -> CurationDefaults.WEIGHT_ANCHOR
        home -> CurationDefaults.WEIGHT_HOME
        else -> CurationDefaults.WEIGHT_GUEST
    }
