// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.passesCeiling
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.AdultMark
import tv.stationbreak.core.rules.RatingLadder

/** The hard-fail rules of the gate (#1035). Each names what it holds, for a failure's text. */
internal enum class Rule(
    val holds: String,
) {
    Ceiling("no title airs on a channel above that channel's ceiling"),
    HorrorVeto("a horror title above the Kids tier never airs on a Kids or Family channel, leftovers included"),
    UnratedOnYoungBand("an unrated or off-ladder title never airs below Adult (the exception allowlist is empty)"),
    FailUpward("a rejection only ever moves a title up a tier"),
    AfterHoursInDefault("After Hours is never a planner pick, and is on only when the fixture opts it in"),
    ExplicitOffAfterHours("an Explicit title airs on After Hours alone: homes, guests and leftovers"),
    ExplicitNotAtHome("with After Hours on, every Explicit title has its home there"),
    AdultOnYoungBand("no Adult-tier title airs on a Kids, Family or Teen channel or on Odds & Ends 33, 66 or 99"),
    BandPromise("a channel numbered in a young band airs under that band's policy and ceiling"),
    NoMetadataInLeftovers("a title with no metadata at all is in no leftovers channel"),
    KidsBandEmpty("the kids fixture has a kids-band themed channel with homes"),
    BelowFloor("no channel is numbered below the lowest number its content allows (Adult 100, Teen 67, Family 34)"),
}

/** One broken rule: where (a pass and a channel key) and what (an item or title ID, never a name). */
internal class Violation(
    val rule: Rule,
    val pass: String,
    val channel: String,
    val subject: String?,
) {
    override fun toString(): String = "${rule.name} [$pass] $channel${subject?.let { " $it" }.orEmpty()}: ${rule.holds}"
}

/**
 * The gate's hard-fail invariants, asserted on a [HouseholdView]. The oracles are the tier ladder (`RatingTier`), the
 * rating ladder and plain metadata reads, never the holds that make the pools: a hold that leaks is what these catch.
 */
internal object GateInvariants {
    /** Unrated titles that may air below Adult on purpose (`#754`): empty until that lands. */
    val UNRATED_EXCEPTIONS: Set<CanonicalId> = emptySet()

    fun check(
        pass: String,
        view: HouseholdView,
        case: GateCase,
        picks: Collection<String>,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        val ctx = Context(view.snapshot)
        val afterHoursKey = AdultMark.AFTER_HOURS_KEY
        if (afterHoursKey in picks) out += Violation(Rule.AfterHoursInDefault, pass, afterHoursKey, "a pick")
        if (!case.afterHours && view.channels.any { it.key == afterHoursKey }) {
            out += Violation(Rule.AfterHoursInDefault, pass, afterHoursKey, "on without an opt-in")
        }
        for (channel in view.channels) {
            out += bandPromise(pass, channel)
            out += belowFloor(pass, channel)
            // What airs on the day, and the whole pool behind it: a leak in the part the epoch cut leaves out counts.
            for (item in (channel.items + channel.pool).distinctBy { it.id }) {
                out += itemRules(pass, channel, ctx, item.id)
            }
        }
        out += explicitHomes(pass, view, ctx)
        if (case.kidsHomes && !hasKidsHomes(view)) out += Violation(Rule.KidsBandEmpty, pass, "kids", null)
        return out
    }

    private fun hasKidsHomes(view: HouseholdView): Boolean =
        view.channels.any { !it.leftovers && it.number in Band.Kids.numbers && it.homed.isNotEmpty() }

    /** The rules that read one item on one channel. */
    private fun itemRules(
        pass: String,
        channel: GateChannel,
        ctx: Context,
        id: CanonicalId,
    ): List<Violation> {
        val entry = ctx.entry(id)
        val series = ctx.series(entry)
        val tier = RatingTier.of(entry, series)
        val limit = limitOf(channel)
        val broken =
            listOfNotNull(
                Rule.Ceiling.takeIf { !CeilingOracle.within(channel, entry, series) },
                Rule.HorrorVeto.takeIf { limit != null && horrorAboveKids(limit, tier, entry, series) },
                Rule.UnratedOnYoungBand.takeIf { limit != null && unrated(id, entry, series) },
                Rule.ExplicitOffAfterHours.takeIf { tier == RatingTier.Explicit && !channel.isAfterHours },
                Rule.AdultOnYoungBand.takeIf { limit != null && tier > limit },
                Rule.NoMetadataInLeftovers.takeIf { channel.leftovers && hasNoMetadata(entry, series) },
            )
        return broken.map { Violation(it, pass, channel.key, id.value) }
    }

    private val GateChannel.isAfterHours: Boolean get() = key == AdultMark.AFTER_HOURS_KEY

    // ADR-0029 rule 2: a kid-rated title with made-for-kids evidence is Kids whatever its genres, Horror included.
    private fun horrorAboveKids(
        limit: RatingTier,
        tier: RatingTier,
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = limit <= RatingTier.Family && tier != RatingTier.Kids && isHorror(entry, series)

    private fun unrated(
        id: CanonicalId,
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = RatingTier.decisiveLevel(entry, series) == null && id !in UNRATED_EXCEPTIONS

    // The tiers a young channel may air: a kid channel the Kids tier, a PG ceiling Family, a TV-14 ceiling Teen.
    private fun limitOf(channel: GateChannel): RatingTier? {
        val level = channel.ceiling?.let(RatingMap::levelOf)
        return when {
            channel.policy == PoolPolicy.KidsOnly -> RatingTier.Kids
            channel.policy == PoolPolicy.ExplicitOnly || level == null -> null
            level <= RatingTier.FAMILY_LEVEL -> RatingTier.Family
            level <= RatingTier.TEEN_LEVEL -> RatingTier.Teen
            else -> null
        }
    }

    private fun isHorror(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = (entry.genres + series?.genres.orEmpty()).any { it.equals("horror", ignoreCase = true) }

    private fun hasNoMetadata(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean {
        val rating = entry.officialRating.isNullOrBlank() && series?.officialRating.isNullOrBlank()
        return rating && (entry.genres + series?.genres.orEmpty() + entry.tags + series?.tags.orEmpty()).isEmpty()
    }

    // A built-in channel in a young band airs under that band's promise, wherever the number came from.
    private fun bandPromise(
        pass: String,
        channel: GateChannel,
    ): List<Violation> {
        val number = channel.number ?: return emptyList()
        val limit = limitOf(channel)
        val kept =
            when (number) {
                in Band.Kids.numbers -> channel.policy == PoolPolicy.KidsOnly
                in Band.Family.numbers -> limit != null && limit <= RatingTier.Family
                in Band.Teen.numbers -> limit != null && limit <= RatingTier.Teen
                else -> true
            }
        return if (kept) emptyList() else listOf(Violation(Rule.BandPromise, pass, channel.key, "number $number"))
    }

    // #1097: a channel never sits below the floor its content allows, as [FloorOracle] reads it (not `ChannelFloors`).
    private fun belowFloor(
        pass: String,
        channel: GateChannel,
    ): List<Violation> {
        val number = channel.number ?: return emptyList()
        val floor = FloorOracle.of(channel)
        return if (number < floor) {
            listOf(Violation(Rule.BelowFloor, pass, channel.key, "number $number below $floor"))
        } else {
            emptyList()
        }
    }

    private fun explicitHomes(
        pass: String,
        view: HouseholdView,
        ctx: Context,
    ): List<Violation> {
        val afterHours = view.channels.firstOrNull { it.key == AdultMark.AFTER_HOURS_KEY } ?: return emptyList()
        val explicit =
            view.snapshot.items
                .filter { RatingTier.of(it, ctx.series(it)) == RatingTier.Explicit }
                .map { it.item.groupId }
                .toSortedSet(compareBy { it.value })
        return explicit
            .filter { view.curated?.homes?.homeOf(it) != afterHours.id }
            .map { Violation(Rule.ExplicitNotAtHome, pass, afterHours.key, it.value) }
    }
}

/** Fail upward on every item of a fixture: a rejection (a tag, a veto, no rating) never lowers a tier. */
internal object FailUpward {
    fun check(snapshot: RuleSnapshot): List<Violation> {
        val ctx = Context(snapshot)
        val out = ArrayList<Violation>()
        for (entry in snapshot.items) {
            val series = ctx.series(entry)
            val tier = RatingTier.of(entry, series)
            val worse =
                buildList {
                    add(RatingTier.of(entry.copy(tags = entry.tags + "nudity"), series) >= RatingTier.Adult)
                    add(RatingTier.of(entry.copy(tags = entry.tags + "porn"), series) == RatingTier.Explicit)
                    for (tag in RatingTier.GORE_TAGS + RatingTier.FAMILY_VETO_TAGS) {
                        add(RatingTier.of(entry.copy(tags = entry.tags + tag), series) >= tier)
                    }
                    add(RatingTier.of(entry.copy(genres = entry.genres + "Horror"), series) >= tier)
                    if (entry.item.seriesId == null) {
                        add(RatingTier.of(entry.copy(officialRating = null), null) >= RatingTier.Adult)
                    }
                }
            if (false in worse) out += Violation(Rule.FailUpward, "fixture", "all", entry.item.id.value)
        }
        return out
    }
}

// The snapshot's items and series by ID.
private class Context(
    private val snapshot: RuleSnapshot,
) {
    private val items = snapshot.items.associateBy { it.item.id }

    fun entry(id: CanonicalId): RuleItem = checkNotNull(items[id]) { "An aired item is not in the snapshot" }

    fun series(entry: RuleItem): RuleSeries? = entry.item.seriesId?.let { snapshot.series[it] }
}

/**
 * The ceiling rule, checked twice. By the pool rules as the evaluation applies them (the kid ceiling on a kids channel,
 * rule 4 with Approved unrated elsewhere). And, apart from the pool code, by the ladder alone: every rating the item
 * and its series carry is on the ladder and at most the ceiling's rank, and an item with no rating fails any ceiling
 * (a rating we cannot read fails up).
 */
internal object CeilingOracle {
    fun within(
        channel: GateChannel,
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = byPoolRules(channel, entry, series) && byLadderRank(channel, entry, series)

    private fun byPoolRules(
        channel: GateChannel,
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        when (channel.policy) {
            PoolPolicy.KidsOnly -> KidHold.passesKidCeiling(entry, series, channel.ceiling)
            else -> passesCeiling(entry, series, channel.ceiling, approvedIsUnrated = channel.policy != PoolPolicy.Open)
        }

    private fun byLadderRank(
        channel: GateChannel,
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean {
        val limit = channel.ceiling?.let { RatingLadder.rankOf(it) }
        val rated = listOf(entry.officialRating, series?.officialRating).filter { !it.isNullOrBlank() }
        val ranks = rated.map { RatingLadder.rankOf(it) }
        val allUnder = limit != null && ranks.isNotEmpty() && ranks.all { it != null && it <= limit }
        return channel.ceiling == null || allUnder
    }
}

/**
 * The floor rule, read apart from `ChannelFloors` (as [CeilingOracle] reads the ceiling apart from the pool code): by
 * the channel's policy and, from the rating ladder alone, the rank of its ceiling. A kid channel may use any number; a
 * ceiling up to PG puts a channel at 34 or up, up to TV-14 at 67 or up; After Hours, a ceiling above that and no
 * ceiling at 100 or up. It is never above the floor `ChannelFloors` gives (a built-in is also held to its recipe's
 * band), so a channel below it is wrong whichever way `ChannelFloors` is wrong.
 */
internal object FloorOracle {
    private val PG_RANK = checkNotNull(RatingLadder.rankOf("PG"))
    private val TV14_RANK = checkNotNull(RatingLadder.rankOf("TV-14"))

    fun of(channel: GateChannel): Int {
        val rank = channel.ceiling?.let { RatingLadder.rankOf(it) }
        return when {
            channel.policy == PoolPolicy.KidsOnly -> Band.Kids.numbers.first
            channel.policy == PoolPolicy.ExplicitOnly || rank == null -> Band.General.numbers.first
            rank <= PG_RANK -> Band.Family.numbers.first
            rank <= TV14_RANK -> Band.Teen.numbers.first
            else -> Band.General.numbers.first
        }
    }
}
