// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * Which recipes and channels are kid ones, and so held to made-for-kids content (spec 18, ADR-0019). Here, in the
 * engine, so the planner ranks a recipe under the same hold the schedule applies to it.
 */
object KidStatus {
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
