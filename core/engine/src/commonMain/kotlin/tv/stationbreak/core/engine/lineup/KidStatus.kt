// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.rules.AdultMark

/**
 * Which recipes and channels are kid ones, and so held to made-for-kids content (spec 18, ADR-0019). Here, in the
 * engine, so the planner ranks a recipe under the same hold the schedule applies to it.
 */
object KidStatus {
    /**
     * A kid recipe is kids-only; After Hours is [PoolPolicy.ExplicitOnly] (#1078); any other (starter) recipe is
     * grown-up (ADR-0020).
     */
    fun policyOf(recipe: Recipe): PoolPolicy =
        when {
            isKidRecipe(recipe) -> PoolPolicy.KidsOnly
            recipe.key == AdultMark.AFTER_HOURS_KEY -> PoolPolicy.ExplicitOnly
            else -> PoolPolicy.GrownUp
        }

    /**
     * The policy of [channel] on its own (ADR-0019, ADR-0020): a kid channel (the kids leftovers included) is
     * kids-only; the After Hours starter, edited or not, is [PoolPolicy.ExplicitOnly] (#1078); any other starter,
     * edited or not, and the grown-up leftovers channel are grown-up; a custom channel (a copy of After Hours included)
     * is [PoolPolicy.Custom] (open, with `Approved` unrated under a ceiling; ADR-0028). It ignores the grown-up
     * leftovers fallback, so it is wrong for a leftovers channel: every pool and every inputs key must use [policyOf]
     * with the lineup. Safe on its own only for a starter or custom channel.
     */
    fun basePolicyOf(channel: LineupChannel): PoolPolicy {
        val source = channel.source
        return when {
            isKidChannel(channel) -> {
                PoolPolicy.KidsOnly
            }

            // A stored After Hours below the floor (#1078: no edit puts it there) is held as grown-up.
            isAfterHoursStarter(channel) && (channel.number ?: Int.MAX_VALUE) >= Band.General.numbers.first -> {
                PoolPolicy.ExplicitOnly
            }

            source is ChannelSource.Starter || source is ChannelSource.Leftovers -> {
                PoolPolicy.GrownUp
            }

            else -> {
                PoolPolicy.Custom
            }
        }
    }

    /**
     * The policy [channel]'s pool is evaluated under in [lineup]: [policyOf] (the channel), except that the grown-up
     * leftovers is [PoolPolicy.Custom] (no kids-tier hold, `Approved` still unrated under a ceiling; ADR-0028; the
     * leftovers pool still drops Explicit and no-metadata titles and keeps a young ceiling's tiers, #1078), not
     * grown-up,
     * while no kid leftovers channel is switched on in [lineup] (ADR-0020). Nothing
     * then takes the made-for-kids items the kid starters do not match, so the grown-up leftovers keeps them (under its
     * own ceiling) and the holds leave nothing airing nowhere. Grown-up starters keep the hold either way.
     *
     * Not day-scoped, and named so: it counts a kid leftovers channel that starts later as on. For one day's holds (a
     * pool, a play key) use [policyOf] with the [LineupDay]; this one is for what spans every day (the inputs key). For
     * a starter or custom channel the lineup makes no difference: [basePolicyOf] says the same without it.
     */
    fun policyAcrossDaysOf(
        channel: LineupChannel,
        lineup: Lineup,
    ): PoolPolicy = policyOf(channel, hasKidLeftoversOn(lineup))

    /**
     * [policyOf] for the holds of [day] (D1, #623): a kid leftovers channel that starts after [day] airs nothing and
     * claims nothing on it (#620), and one that has left by [day] airs nothing, so neither counts as on: the grown-up
     * leftovers is [PoolPolicy.Custom] for the day. From its boundary it counts again.
     */
    fun policyOf(
        channel: LineupChannel,
        lineup: Lineup,
        day: LineupDay,
    ): PoolPolicy = policyOf(channel, hasKidLeftoversOn(lineup, day))

    private fun policyOf(
        channel: LineupChannel,
        kidLeftoversOn: Boolean,
    ): PoolPolicy {
        val policy = basePolicyOf(channel)
        val grownUpLeftovers = policy == PoolPolicy.GrownUp && channel.source is ChannelSource.Leftovers
        return if (grownUpLeftovers && !kidLeftoversOn) PoolPolicy.Custom else policy
    }

    // Whether [lineup] has a kid leftovers channel (Kids' Odds & Ends) switched on, whenever it starts: the day-less
    // [policyAcrossDaysOf] only. Private so that nothing else can ask it for a day's hold (#629).
    private fun hasKidLeftoversOn(lineup: Lineup): Boolean = lineup.channels.any { isKidLeftoversOn(it) }

    /** Whether [lineup] has a kid leftovers channel on the air on [day] ([isOnAirOn]); one starting later is off. */
    fun hasKidLeftoversOn(
        lineup: Lineup,
        day: LineupDay,
    ): Boolean = lineup.channels.any { isKidLeftoversOn(it) && it.isOnAirOn(day) }

    private fun isKidLeftoversOn(channel: LineupChannel): Boolean =
        channel.source is ChannelSource.Leftovers && channel.state == ChannelState.On && isKidChannel(channel)

    /**
     * A kid recipe: kids-only (the Kids band, or one of the kids recipes kept outside it), or a key that was ever
     * kid-safe and is not a Family recipe now.
     */
    fun isKidRecipe(recipe: Recipe): Boolean = recipe.kidSafe || isKidKey(recipe.key)

    // Ever kid-safe, and not one of the keys that moved to the Family band at catalog 8.
    private fun isKidKey(key: String): Boolean {
        val everKidSafe = key in StarterCatalog.EVER_KID_SAFE_KEYS
        return everKidSafe && key !in StarterCatalog.KIDS_KEYS_NOW_FAMILY
    }

    /**
     * A kid channel: numbered in the Kids band, or a key that was ever kid-safe (whatever its number or edit). A
     * retired kid recipe's channel is Custom but keeps its `starter:<key>` or `leftovers:<key>` ID, so the key is read
     * from the ID there and the kids-only hold survives retirement (#1044). A viewer's own channel is `custom:<id>`
     * and never matches.
     */
    fun isKidChannel(channel: LineupChannel): Boolean {
        val inKidsBand = channel.number?.let { it in Band.Kids.numbers } == true
        val key =
            when (val source = channel.source) {
                is ChannelSource.Starter -> {
                    source.recipeKey
                }

                is ChannelSource.Leftovers -> {
                    source.recipeKey
                }

                is ChannelSource.Custom -> {
                    channel.id.value
                        .takeIf { it.startsWith("starter:") || it.startsWith("leftovers:") }
                        ?.substringAfter(':')
                }

                else -> {
                    null
                }
            }
        return inKidsBand || key?.let(::isKidKey) == true
    }
}

/** Whether [channel] is the After Hours starter, edited or not (not a custom copy). */
internal fun isAfterHoursStarter(channel: LineupChannel): Boolean {
    val source = channel.source as? ChannelSource.Starter
    return source?.recipeKey == AdultMark.AFTER_HOURS_KEY
}
