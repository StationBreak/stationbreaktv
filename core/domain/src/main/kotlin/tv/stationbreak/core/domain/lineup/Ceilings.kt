// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.rules.RatingLadder

/**
 * The rating ceiling a channel is scheduled under, which never comes from the stored lineup alone ([of]). A leftovers
 * channel takes the catalog's ceiling while its key is in the catalog (the stored one only counts for a retired key).
 * An unedited starter of a kid-safe recipe takes the recipe's; an edited one keeps its stored ceiling. A channel whose
 * key was ever kid-safe (`StarterCatalog.EVER_KID_SAFE_KEYS`, whatever its number or edit), and any channel numbered in
 * the Kids band, is never above PG: a ceiling that is null or higher becomes PG.
 */
internal class Ceilings(
    private val catalog: StarterCatalogView,
) {
    /** The ceiling [channel] is scheduled under, or null for none. */
    fun of(channel: LineupChannel): String? {
        val stored = storedCeiling(channel.source)
        return if (isKidChannel(channel) && !atMostPg(stored)) KIDS_MAX_RATING else stored
    }

    // The ceiling before the kids' cap: the catalog's wherever it governs, else the stored one.
    private fun storedCeiling(source: ChannelSource): String? =
        when (source) {
            is ChannelSource.Leftovers -> leftoversCeiling(source)
            is ChannelSource.Starter -> starterCeiling(source)
            is ChannelSource.Custom -> source.rules.maxRating
            is ChannelSource.Unusable -> null
        }

    private fun starterCeiling(source: ChannelSource.Starter): String? {
        val recipeCeiling = catalog.recipes.firstOrNull { it.key == source.recipeKey }?.maxRating
        return if (recipeCeiling != null && !source.edited) recipeCeiling else source.rules.maxRating
    }

    // The catalog's ceiling, null included, while the key is in it; the stored one only for a retired key.
    private fun leftoversCeiling(source: ChannelSource.Leftovers): String? {
        val recipe = catalog.leftovers.firstOrNull { it.key == source.recipeKey }
        return if (recipe != null) recipe.maxRating else source.maxRating
    }

    private companion object {
        /** The most a kids' channel may air: the Kids band's maximum. */
        const val KIDS_MAX_RATING = "PG"
    }
}

// True for a ceiling on the ladder at PG or lower; null (no ceiling) and unknown names are not.
private fun atMostPg(rating: String?): Boolean {
    val rank = RatingLadder.rankOf(rating)
    return rank != null && rank <= checkNotNull(RatingLadder.rankOf("PG"))
}
