// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.Filters
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
    ;

    companion object {
        /** The band [number] is in, or null when it is in none (the custom, seasonal and any other range). */
        fun of(number: Int): Band? = entries.firstOrNull { number in it.numbers }
    }
}

/**
 * A themed channel the catalog can fill from a library. [sources] and [exclusions] are portable (genre, tag, year
 * range); [collectionHints] are collection names turned into collection sources by ID, from the collections the server
 * has now. A kid-safe recipe (the Kids band) carries a [maxRating], a rating-ladder name no higher than PG; a grown-up
 * recipe carries none.
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
    val filters: Filters? = null,
    val theme: Theme? = null,
) {
    init {
        require(AutoSeed.isValidRecipeKey(key)) { "The recipe key is not valid" }
        require(number in band.numbers) { "The number is outside the band" }
        require(kidSafe == (maxRating != null)) { "A recipe is kid-safe exactly when it has a rating ceiling" }
        require(maxRating == null || maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
        if (kidSafe) require(atMostPg(maxRating)) { "A kid-safe recipe has a ceiling of PG or lower" }
        require(sources.isNotEmpty()) { "A recipe needs a source" }
        require((sources + exclusions).all(::isPortable)) { "A recipe uses genre, tag, studio, year range" }
        if (theme != null) {
            require(ordering == OrderingMode.Curated) { "A themed recipe is programmed (curated)" }
            require(collectionHints.isEmpty()) { "A themed recipe has no collection hints" }
        }
    }

    /** True for the Kids band. */
    val kidSafe: Boolean get() = band == Band.Kids

    /** True when the channel is programmed: its order is curated. */
    val curated: Boolean get() = ordering == OrderingMode.Curated

    /** True for a guest-only channel: one of its sources is a year range (a decade). */
    val guestOnly: Boolean get() = sources.any { it is Source.YearRange }

    /** The genres the recipe is about: its genre sources, then the genres of its filters, once each, in order. */
    val themeGenres: List<String> by lazy {
        (sources.filterIsInstance<Source.Genre>().map { it.name } + filters?.genres.orEmpty()).distinct()
    }

    /** The tags the recipe is about: its tag sources, then the tags of its filters, once each, in order. */
    val themeTags: List<String> by lazy {
        (sources.filterIsInstance<Source.Tag>().map { it.name } + filters?.tags.orEmpty()).distinct()
    }
}

private fun atMostPg(rating: String?): Boolean {
    val rank = RatingLadder.rankOf(rating)
    return rank != null && rank <= checkNotNull(RatingLadder.rankOf("PG"))
}

private fun isPortable(source: Source): Boolean =
    when (source) {
        is Source.Genre, is Source.Tag, is Source.YearRange, is Source.Studio -> true
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
        require(maxRating == null || maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
        if (number in Band.Kids.numbers) {
            require(atMostPg(maxRating)) { "Kids' leftovers need a ceiling of PG or lower" }
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

    /**
     * True when the planner chooses this catalog's starters by identity (`Selection.select`, spec 23) rather than by
     * ranking: catalog version 3 on ([StarterCatalog.SELECTS_BY_IDENTITY_FROM]). Every recipe of such a catalog is
     * themed (a test holds the shipped catalog to it).
     */
    val selectsByIdentity: Boolean
}
