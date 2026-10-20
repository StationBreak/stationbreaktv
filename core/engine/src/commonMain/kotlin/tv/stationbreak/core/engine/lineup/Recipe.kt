// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source

/** The cable-style number bands. Shipped default numbers stay inside their band and are never reused. */
@Suppress("MagicNumber")
enum class Band(
    val numbers: IntRange,
) {
    Kids(1..99),
    General(100..199),
    Drama(200..299),
    Genre(300..399),
    World(400..499),
    Premium(500..599),
}

/**
 * A themed channel the catalog can fill from a library. [sources] and [exclusions] are portable (genre, tag, year
 * range); [collectionHints] are collection names turned into collection sources by ID, from the collections the server
 * has now. A kid-safe recipe (the Kids band) carries a [maxRating]; a grown-up recipe carries none.
 */
data class Recipe(
    val key: String,
    val name: String,
    val personality: String,
    val band: Band,
    val number: Int,
    val optIn: Boolean,
    val sources: List<Source>,
    val exclusions: List<Source>,
    val maxRating: String?,
    val ordering: OrderingMode?,
    val collectionHints: List<String>,
) {
    init {
        require(AutoSeed.isValidRecipeKey(key)) { "The recipe key is not valid" }
        require(number in band.numbers) { "The number is outside the band" }
        require(kidSafe == (maxRating != null)) { "A recipe is kid-safe exactly when it has a rating ceiling" }
        require(sources.isNotEmpty()) { "A recipe needs a source" }
        require((sources + exclusions).all(::isPortable)) { "A recipe uses genre, tag and year range sources only" }
    }

    /** True for the Kids band. */
    val kidSafe: Boolean get() = band == Band.Kids
}

private fun isPortable(source: Source): Boolean =
    when (source) {
        is Source.Genre, is Source.Tag, is Source.YearRange -> true
        else -> false
    }

/** A channel that airs what no other switched-on channel does. Its pool is computed by the engine, not by rules. */
data class LeftoversRecipe(
    val key: String,
    val name: String,
    val personality: String,
    val number: Int,
    val maxRating: String?,
) {
    init {
        require(AutoSeed.isValidRecipeKey(key)) { "The recipe key is not valid" }
        require(number in Band.Kids.numbers || number in Band.General.numbers) {
            "The number is outside the Kids and General bands"
        }
        if (number in Band.Kids.numbers) {
            val rank = RatingLadder.rankOf(maxRating)
            val limit = checkNotNull(RatingLadder.rankOf("PG"))
            require(rank != null && rank <= limit) { "Kids' leftovers need a ceiling of PG or lower" }
        } else {
            require(maxRating == null) { "Grown-up leftovers have no rating ceiling" }
        }
    }
}

/** What the lineup builder reads from a catalog. Tests pass small ones. */
interface StarterCatalogView {
    val version: Int
    val recipes: List<Recipe>
    val leftovers: List<LeftoversRecipe>

    /** Numbers that shipped once and are never used again. */
    val retiredNumbers: Set<Int>

    /** Keys that shipped once and are never used again. */
    val retiredKeys: Set<String>
}
