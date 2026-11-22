// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.rules.RatingLadder

/**
 * The rating ceiling a channel is scheduled under, which never comes from the stored lineup alone ([of]). A leftovers
 * channel takes the catalog's ceiling while its key is in the catalog (the stored one only counts for a retired key).
 * An unedited starter of a kid-safe recipe takes the recipe's; an edited one keeps its stored ceiling. A channel whose
 * key was ever kid-safe (`StarterCatalog.EVER_KID_SAFE_KEYS`, whatever its number or edit), and any channel numbered in
 * the Kids band, is never above PG: a ceiling that is null or higher becomes PG. A built-in channel (a starter or a
 * leftovers channel) numbered in the Family band (34 to 66) is never above PG, and in the Teen band (67 to 99) never
 * above TV-14 (#1078): its band's promise holds wherever it is moved, and the grown-up hold reads that ceiling as the
 * band's tier limit (`GrownUpHold.tierLimit`). A retired recipe's channel (Custom, but with a `starter:` or
 * `leftovers:` ID) counts as built-in (#1088). A viewer's own custom channel keeps its own ceiling there: no hidden
 * filters.
 */
object ChannelCeilings {
    /** The ceiling [channel] is scheduled under in [catalog], or null for none. */
    fun of(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): String? {
        val stored = storedCeiling(channel.source, catalog)
        val cap = if (isPgCapped(channel)) KIDS_MAX_RATING else youngBandCap(channel)
        return if (cap != null && !atMost(stored, cap)) cap else stored
    }

    // The most a built-in channel numbered in the Family or Teen band may air (#1078); null for any other channel.
    private fun youngBandCap(channel: LineupChannel): String? {
        val builtIn = isBuiltIn(channel)
        return when (channel.number?.takeIf { builtIn }) {
            in Band.Family.numbers -> KIDS_MAX_RATING
            in Band.Teen.numbers -> TEEN_MAX_RATING
            else -> null
        }
    }

    // A starter or leftovers channel, or a retired one: Custom but keeping its `starter:` or `leftovers:` ID (#1088),
    // as `KidStatus.isKidChannel` reads it.
    private fun isBuiltIn(channel: LineupChannel): Boolean =
        when (channel.source) {
            is ChannelSource.Starter, is ChannelSource.Leftovers -> {
                true
            }

            is ChannelSource.Custom -> {
                channel.id.value.startsWith("starter:") || channel.id.value.startsWith("leftovers:")
            }

            is ChannelSource.Unusable -> {
                false
            }
        }

    // The ceiling before the kids' cap: the catalog's wherever it governs, else the stored one.
    private fun storedCeiling(
        source: ChannelSource,
        catalog: StarterCatalogView,
    ): String? =
        when (source) {
            is ChannelSource.Leftovers -> leftoversCeiling(source, catalog)
            is ChannelSource.Starter -> starterCeiling(source, catalog)
            is ChannelSource.Custom -> source.rules.maxRating
            is ChannelSource.Unusable -> null
        }

    private fun starterCeiling(
        source: ChannelSource.Starter,
        catalog: StarterCatalogView,
    ): String? {
        val recipeCeiling = catalog.recipes.firstOrNull { it.key == source.recipeKey }?.maxRating
        return if (recipeCeiling != null && !source.edited) recipeCeiling else source.rules.maxRating
    }

    // The catalog's ceiling, null included, while the key is in it; the stored one only for a retired key.
    private fun leftoversCeiling(
        source: ChannelSource.Leftovers,
        catalog: StarterCatalogView,
    ): String? {
        val recipe = catalog.leftovers.firstOrNull { it.key == source.recipeKey }
        return if (recipe != null) recipe.maxRating else source.maxRating
    }

    // A kid channel, or a Family channel that was kid-safe before catalog 8 (an ever-kid-safe key): held to PG always.
    private fun isPgCapped(channel: LineupChannel): Boolean =
        isKidChannel(channel) ||
            (channel.source as? ChannelSource.Starter)?.recipeKey in StarterCatalog.EVER_KID_SAFE_KEYS

    // The most a kids' channel may air (the Kids band's maximum), and the most a Teen-band channel may air.
    private const val KIDS_MAX_RATING = "PG"
    private const val TEEN_MAX_RATING = "TV-14"
}

// True for a ceiling on the ladder at [limit] or lower; null (no ceiling) and unknown names are not.
private fun atMost(
    rating: String?,
    limit: String,
): Boolean {
    val rank = RatingLadder.rankOf(rating)
    return rank != null && rank <= checkNotNull(RatingLadder.rankOf(limit))
}
