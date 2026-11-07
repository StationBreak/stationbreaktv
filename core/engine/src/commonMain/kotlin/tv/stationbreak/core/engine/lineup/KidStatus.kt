// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolPolicy

/**
 * Which recipes and channels are kid ones, and so held to made-for-kids content (spec 18, ADR-0019). Here, in the
 * engine, so the planner ranks a recipe under the same hold the schedule applies to it.
 */
object KidStatus {
    /** A kid recipe is kids-only; any other (starter) recipe is grown-up (ADR-0020). */
    fun policyOf(recipe: Recipe): PoolPolicy = if (isKidRecipe(recipe)) PoolPolicy.KidsOnly else PoolPolicy.GrownUp

    /**
     * The policy of [channel] on its own (ADR-0019, ADR-0020): a kid channel (the kids leftovers included) is
     * kids-only; any other starter, edited or not, and the grown-up leftovers channel are grown-up; a custom channel
     * is open. It ignores the grown-up leftovers fallback, so it is wrong for a leftovers channel: every pool and
     * every inputs key must use [policyOf] with the lineup. Safe on its own only for a starter or custom channel.
     */
    fun basePolicyOf(channel: LineupChannel): PoolPolicy =
        when {
            isKidChannel(channel) -> PoolPolicy.KidsOnly
            channel.source is ChannelSource.Starter || channel.source is ChannelSource.Leftovers -> PoolPolicy.GrownUp
            else -> PoolPolicy.Open
        }

    /**
     * The policy [channel]'s pool is evaluated under in [lineup]: [policyOf] (the channel), except that the grown-up
     * leftovers is open, not grown-up, while no kid leftovers channel is switched on in [lineup] (ADR-0020). Nothing
     * then takes the made-for-kids items the kid starters do not match, so the grown-up leftovers keeps them (under its
     * own ceiling) and the holds leave nothing airing nowhere. Grown-up starters keep the hold either way.
     *
     * Not day-scoped: it counts a kid leftovers channel that starts later as on. For one day's holds (a pool, a play
     * key) use the overload with the [LineupDay]; this one is for what spans every day (the inputs key).
     */
    fun policyOf(
        channel: LineupChannel,
        lineup: Lineup,
    ): PoolPolicy = policyOf(channel, hasKidLeftoversOn(lineup))

    /**
     * [policyOf] for the holds of [day] (D1, #623): a kid leftovers channel that starts after [day] airs nothing and
     * claims nothing on it (#620), and one that has left by [day] airs nothing, so neither counts as on: the grown-up
     * leftovers is open for the day. From its boundary it counts again.
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
        return if (grownUpLeftovers && !kidLeftoversOn) PoolPolicy.Open else policy
    }

    /** Whether [lineup] has a kid leftovers channel (Kids' Odds & Ends) switched on, whenever it starts. */
    fun hasKidLeftoversOn(lineup: Lineup): Boolean = lineup.channels.any { isKidLeftoversOn(it) }

    /** Whether [lineup] has a kid leftovers channel on the air on [day] ([isOnAirOn]); one starting later is off. */
    fun hasKidLeftoversOn(
        lineup: Lineup,
        day: LineupDay,
    ): Boolean = lineup.channels.any { isKidLeftoversOn(it) && it.isOnAirOn(day) }

    private fun isKidLeftoversOn(channel: LineupChannel): Boolean =
        channel.source is ChannelSource.Leftovers && channel.state == ChannelState.On && isKidChannel(channel)

    /** A kid recipe: in the Kids band, or a key that was ever kid-safe. */
    fun isKidRecipe(recipe: Recipe): Boolean {
        val everKidSafe = recipe.key in StarterCatalog.EVER_KID_SAFE_KEYS
        return recipe.band == Band.Kids || everKidSafe
    }

    /** A kid channel: numbered in the Kids band, or a key that was ever kid-safe (whatever its number or edit). */
    fun isKidChannel(channel: LineupChannel): Boolean {
        val inKidsBand = channel.number?.let { it in Band.Kids.numbers } == true
        val key =
            when (val source = channel.source) {
                is ChannelSource.Starter -> source.recipeKey
                is ChannelSource.Leftovers -> source.recipeKey
                else -> null
            }
        return inKidsBand || key?.let { it in StarterCatalog.EVER_KID_SAFE_KEYS } == true
    }
}
