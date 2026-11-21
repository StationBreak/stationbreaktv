// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.lineup.curation.CurationDefaults
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source

/**
 * The cable-style number bands. Shipped default numbers stay inside their band and are never reused (catalog 8 moved
 * the young channels once, with the owner's pre-release waiver; see [StarterCatalog]). The three young bands are age
 * ordered (#1002, ADR-0028): Kids 1 to 33, Family 34 to 66, Teen 67 to 99. Each carries a rating ceiling.
 */
@Suppress("MagicNumber")
enum class Band(
    val numbers: IntRange,
) {
    Kids(1..33),
    Family(34..66),
    Teen(67..99),
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

    /** True for the three young bands, whose channels carry a rating ceiling. */
    val young: Boolean get() = this == Kids || this == Family || this == Teen
}

/** The highest ceiling a young band allows: PG (Kids, Family) or TV-14 (Teen, the PG-13 / TV-14 tier). */
internal fun Band.ceilingLimit(): String? =
    when (this) {
        Band.Kids, Band.Family -> "PG"
        Band.Teen -> "TV-14"
        else -> null
    }

private fun withinLimit(
    rating: String?,
    limit: String,
): Boolean {
    val rank = RatingLadder.rankOf(rating)
    return rank != null && rank <= checkNotNull(RatingLadder.rankOf(limit))
}

/**
 * What a starter must hold to qualify as a channel (catalog 5, #786). It is recipe data, frozen in the catalog
 * manifest, so it depends on the catalog and the library only, never on which channels are on, and the selection
 * rounds still settle. [Standard] is the global bar (12 usable hours, 6 titles; a kid channel 4 titles). [Niche] is 6
 * usable hours and 4 titles, so a thin shelf of a niche theme still gets a channel. Which recipes carry it is a
 * judgement call, recorded by name in the catalog (`CatalogV3Recipes`, the `bar = QualifyingBar.Niche` recipes) and
 * pinned in `CatalogGapsTest`; no rule derives it. A kid recipe never has it. An edited starter is not selected by the
 * planner, and keeps its recipe's bar wherever the planner reads its stats.
 */
enum class QualifyingBar(
    val usableMs: Long,
    val titles: Int,
) {
    Standard(CurationDefaults.MIN_USABLE_MS, CurationDefaults.MIN_TITLES),
    Niche(CurationDefaults.MIN_USABLE_MS_NICHE, CurationDefaults.MIN_TITLES_NICHE),
}

/**
 * A themed channel the catalog can fill from a library. [sources] and [exclusions] are portable (genre, tag, year
 * range); [collectionHints] are collection names turned into collection sources by ID, from the collections the server
 * has now. A young-band recipe (Kids, Family, Teen) carries a [maxRating], a rating-ladder name no higher than its
 * band's limit (PG, PG, TV-14); a grown-up recipe carries none. [qualifyingBar] is the standard one unless the
 * catalog names the recipe as the single lane of a niche theme; a kid recipe always has the standard bar.
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
    val qualifyingBar: QualifyingBar = QualifyingBar.Standard,
) {
    init {
        require(AutoSeed.isValidRecipeKey(key)) { "The recipe key is not valid" }
        require(number in band.numbers) { "The number is outside the band" }
        require(band.young == (maxRating != null)) { "A young-band recipe has a rating ceiling, any other has none" }
        require(maxRating == null || maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
        band.ceilingLimit()?.let { require(withinLimit(maxRating, it)) { "The ceiling is above the band's limit" } }
        require(sources.isNotEmpty()) { "A recipe needs a source" }
        require((sources + exclusions).all(::isPortable)) { "A recipe uses genre, tag, studio, year range" }
        require(!kidSafe || qualifyingBar == QualifyingBar.Standard) { "A kid recipe has the standard qualifying bar" }
        if (theme != null) {
            require(ordering == OrderingMode.Curated) { "A themed recipe is programmed (curated)" }
            require(collectionHints.isEmpty()) { "A themed recipe has no collection hints" }
        }
    }

    /** True for a kids-only recipe: one in the Kids band. */
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
        val band = Band.of(number)
        require(band != null && (band.young || band == Band.General)) {
            "The number is outside the Kids, Family, Teen and General bands"
        }
        require(maxRating == null || maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
        val limit = band.ceilingLimit()
        if (limit != null) {
            require(withinLimit(maxRating, limit)) { "A young band's leftovers need a ceiling within the band's limit" }
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
