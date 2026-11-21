// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source

/** What a shipped recipe promised: its number, band, opt-in flag and ceiling, and a [fingerprint] of what it airs. */
internal data class ManifestRecipe(
    val number: Int,
    val band: Band,
    val optIn: Boolean,
    val maxRating: String?,
    val fingerprint: String,
) {
    companion object {
        fun of(recipe: Recipe): ManifestRecipe {
            val fingerprint = fingerprintOf(recipe)
            return ManifestRecipe(recipe.number, recipe.band, recipe.optIn, recipe.maxRating, fingerprint)
        }
    }
}

/** What a shipped leftovers recipe promised. */
internal data class ManifestLeftovers(
    val number: Int,
    val maxRating: String?,
) {
    companion object {
        fun of(recipe: LeftoversRecipe) = ManifestLeftovers(recipe.number, recipe.maxRating)
    }
}

/** A frozen record of one shipped catalog version. Never edit one; a new catalog version adds a new manifest. */
internal class CatalogManifest(
    val version: Int,
    val recipes: Map<String, ManifestRecipe>,
    val leftovers: Map<String, ManifestLeftovers>,
)

/**
 * A fingerprint of what [recipe] airs: its sources, exclusions, collection hints and ordering, written as one canonical
 * string (every text length-prefixed, so no separator can be faked) and hashed with 64-bit FNV-1a over its UTF-8 bytes.
 * Nothing platform-specific goes in (no `hashCode`), so the JVM and linuxX64 give the same value.
 */
internal fun fingerprintOf(recipe: Recipe): String {
    val canonical =
        buildString {
            append("sources[")
            recipe.sources.forEach { append(canonicalOf(it)) }
            append("]exclusions[")
            recipe.exclusions.forEach { append(canonicalOf(it)) }
            append("]hints[")
            recipe.collectionHints.forEach { append(text(it)) }
            append("]ordering[")
            append(recipe.ordering?.wireName?.let(::text) ?: "-")
            append("]")
            recipe.filters?.let { append("filters[").append(canonicalOf(it)).append("]") }
            recipe.theme?.let { append("theme[").append(canonicalOf(it)).append("]") }
            // Catalog 5: only a lower bar is written, so a recipe at the standard one keeps its earlier fingerprint.
            if (recipe.qualifyingBar != QualifyingBar.Standard) {
                // The values are in it, so changing what the lower bar means needs a newer catalog.
                val bar = recipe.qualifyingBar
                append("bar[")
                    .append(bar.usableMs)
                    .append(',')
                    .append(bar.titles)
                    .append("]")
            }
            // Catalog 12: only a seasonal recipe writes its season and window, so every other keeps its fingerprint.
            recipe.season?.let {
                val w = it.window
                append("season[")
                    .append(it.name)
                    .append(',')
                    .append(w.startMonth)
                    .append('/')
                    .append(w.startDay)
                    .append('-')
                    .append(w.endMonth)
                    .append('/')
                    .append(w.endDay)
                    .append("]")
            }
        }
    return fnv1a64(canonical.encodeToByteArray()).toString(HEX_DIGITS).padStart(HEX_DIGITS, '0')
}

/**
 * The one owner-approved exception to "a shipped number never changes" (pre-release only, #1002, see [StarterCatalog]):
 * the keys catalog 8 moved, with the number each took. A catalog at version 8 or later may have these keys at these
 * numbers (and in their new bands) against any earlier manifest; any other move still fails. The never-reuse rule
 * resumes after v1.
 */
internal val NUMBERS_WAIVED_AT_8: Map<String, Int> =
    mapOf(
        "little-farm" to 2,
        "sing-along-station" to 4,
        "saturday-morning" to 6,
        "backyard-fort" to 8,
        "critter-channel" to 10,
        "robot-factory" to 12,
        "hey-science" to 14,
        "pocket-heroes" to 16,
        "flipbook" to 18,
        "snowglobe" to 20,
        "giggle-garage" to 22,
        "wheels-and-whistles" to 24,
        "small-sleuths" to 26,
        "once-upon" to 36,
        "rainy-day-matinee" to 39,
        "little-spirits" to 42,
        "spooky-sleepover" to 45,
        "kids-odds-and-ends" to 33,
    )

/**
 * The same exception for catalog 12 (the seasonal band, #592, owner-approved pre-release, the issue says the two need
 * not keep their numbers): the two holiday keys and the number each took in the Seasonal band. Their old numbers (182
 * and 340) are retired. A catalog at version 12 or later may have these keys at these numbers against any earlier
 * manifest; any other move still fails.
 */
internal val NUMBERS_WAIVED_AT_12: Map<String, Int> =
    mapOf(
        "always-december" to 900,
        "jack-o-lantern-lounge" to 910,
    )

/**
 * The same exception for catalog 13 (Oddly Funny into General, #1000, owner-approved pre-release): the key and the
 * number it took. Its old number (528) is retired. A catalog at version 13 or later may have this key at this number
 * against any earlier manifest; any other move still fails.
 */
internal val NUMBERS_WAIVED_AT_13: Map<String, Int> = mapOf("satire-desk" to 190)

private fun waived(
    catalog: StarterCatalogView,
    manifest: CatalogManifest,
    key: String,
    number: Int,
): Boolean =
    (manifest.version < 8 && catalog.version >= 8 && NUMBERS_WAIVED_AT_8[key] == number) ||
        (manifest.version < 12 && catalog.version >= 12 && NUMBERS_WAIVED_AT_12[key] == number) ||
        (manifest.version < 13 && catalog.version >= 13 && NUMBERS_WAIVED_AT_13[key] == number)

/**
 * What [catalog] breaks of [manifest]'s promises, empty when it keeps them all. Every shipped key is still in the
 * catalog at the same number and band, or is retired with its number. A catalog that has not moved past the
 * manifest's version has exactly the manifest's recipes and leftovers, with equal entries.
 */
internal fun manifestProblems(
    catalog: StarterCatalogView,
    manifest: CatalogManifest,
): List<String> {
    val problems = mutableListOf<String>()
    val shippedRecipes = manifest.recipes.mapValues { (_, shipped) -> shipped.inTodaysBands(manifest.version) }
    val recipes = catalog.recipes.associateBy { it.key }
    val leftovers = catalog.leftovers.associateBy { it.key }
    for ((key, shipped) in shippedRecipes) {
        val recipe = recipes[key]
        if (recipe == null) {
            problems += retirementProblems(catalog, key, shipped.number)
        } else if (moved(recipe, shipped) && !waived(catalog, manifest, key, recipe.number)) {
            problems += "$key moved from ${shipped.number} (${shipped.band}) to ${recipe.number} (${recipe.band})"
        }
    }
    for ((key, shipped) in manifest.leftovers) {
        val recipe = leftovers[key]
        if (recipe == null) {
            problems += retirementProblems(catalog, key, shipped.number)
        } else if (recipe.number != shipped.number && !waived(catalog, manifest, key, recipe.number)) {
            problems += "leftovers $key moved from ${shipped.number} to ${recipe.number}"
        }
    }
    if (catalog.version <= manifest.version) {
        val now = catalog.recipes.associate { it.key to ManifestRecipe.of(it) }
        val changed = (now.keys + shippedRecipes.keys).filter { now[it] != shippedRecipes[it] }.sorted()
        if (changed.isNotEmpty()) problems += "recipes changed without a new catalog version: $changed"
        val nowLeftovers = catalog.leftovers.associate { it.key to ManifestLeftovers.of(it) }
        val keys = nowLeftovers.keys + manifest.leftovers.keys
        val changedLeftovers = keys.filter { nowLeftovers[it] != manifest.leftovers[it] }.sorted()
        if (changedLeftovers.isNotEmpty()) {
            problems += "leftovers changed without a new catalog version: $changedLeftovers"
        }
    }
    return problems
}

// The Kids band was 1 to 99 before catalog 8; a manifest of an earlier version says Kids for every number in it, and
// the
// number tells the band it is in today (Kids, Family or Teen). Manifests are never edited, so this reads them.
private fun ManifestRecipe.inTodaysBands(version: Int): ManifestRecipe =
    if (version < 8 && band == Band.Kids) copy(band = checkNotNull(Band.of(number))) else this

private fun moved(
    recipe: Recipe,
    shipped: ManifestRecipe,
): Boolean = recipe.number != shipped.number || recipe.band != shipped.band

private fun retirementProblems(
    catalog: StarterCatalogView,
    key: String,
    number: Int,
): List<String> =
    when {
        key !in catalog.retiredKeys -> listOf("$key left the catalog without being retired")
        number !in catalog.retiredNumbers -> listOf("$key was retired but its number $number was not")
        else -> emptyList()
    }

// Catalog sources carry no unknown fields (a test checks it), so extras stay out of the text; one would fail here.
private fun canonicalOf(source: Source): String {
    require(source.extras.isEmpty()) { "A catalog source has no extras" }
    val body =
        when (source) {
            is Source.Genre -> "genre" + text(source.name)
            is Source.Tag -> "tag" + text(source.name)
            is Source.YearRange -> "years(${source.from},${source.to})"
            is Source.Collection -> "collection(${source.id.value})"
            is Source.Library -> "library(${source.id.value})"
            is Source.Series -> "series(${source.id.value})"
            is Source.Item -> "item(${source.id.value})"
            is Source.Studio -> "studio" + text(source.name)
        }
    return "$body;"
}

private fun canonicalOf(filters: Filters): String =
    buildString {
        val lists = listOf("g" to filters.genres, "t" to filters.tags, "s" to filters.studios, "n" to filters.notGenres)
        for ((label, names) in lists) {
            append(label).append('[')
            names?.forEach { append(text(it)) }
            append(names?.let { "]" } ?: "-]")
        }
        append("years").append(filters.yearFrom).append(':').append(filters.yearTo)
        append("run").append(filters.minRuntimeMinutes).append(':').append(filters.maxRuntimeMinutes)
        append("ep").append(filters.episodeMinRuntimeMinutes).append(':').append(filters.episodeMaxRuntimeMinutes)
        append("mv").append(filters.movieMinRuntimeMinutes).append(':').append(filters.movieMaxRuntimeMinutes)
        append("kind").append(filters.kind?.wireName?.let(::text) ?: "-")
        append("extras").append(canonicalOf(filters.extras))
        append("yearsExtras").append(canonicalOf(filters.yearsExtras))
        append("runtimeExtras").append(canonicalOf(filters.runtimeExtras))
        append("episodeRuntimeExtras").append(canonicalOf(filters.episodeRuntimeExtras))
        append("movieRuntimeExtras").append(canonicalOf(filters.movieRuntimeExtras))
    }

// JSON with object keys in sorted order, every text length-prefixed.
private fun canonicalOf(element: JsonElement): String =
    when (element) {
        is JsonObject -> {
            element.entries
                .sortedBy { it.key }
                .joinToString(",", "{", "}") { text(it.key) + canonicalOf(it.value) }
        }

        is JsonArray -> {
            element.joinToString(",", "[", "]") { canonicalOf(it) }
        }

        is JsonNull -> {
            "null"
        }

        is JsonPrimitive -> {
            if (element.isString) "s" + text(element.content) else "p" + text(element.content)
        }
    }

private fun canonicalOf(matcher: NameMatcher): String =
    when (matcher) {
        is NameMatcher.Exact -> "exact" + text(matcher.folded)
        is NameMatcher.Prefix -> "prefix" + text(matcher.folded)
        is NameMatcher.Contains -> "contains" + text(matcher.folded)
        is NameMatcher.Word -> "word" + text(matcher.folded)
    }

private fun kindTag(kind: AnchorKind): String =
    when (kind) {
        AnchorKind.Collection -> "collection"
        AnchorKind.Title -> "title"
        AnchorKind.Studio -> "studio"
    }

private fun canonicalOf(theme: Theme): String =
    buildString {
        append("anchors[")
        theme.anchors.forEach { append(kindTag(it.kind)).append(canonicalOf(it.match)) }
        append("]studios[")
        theme.themeStudios.forEach { append(canonicalOf(it)) }
        append("]format").append(
            when (theme.format) {
                null -> "-"
                FormatPromise.Films -> "films"
                FormatPromise.ShortSeries -> "short-series"
            },
        )
        append("anime").append(theme.animeOnly)
    }

// A text with its UTF-8 byte length in front, so the canonical string cannot be read two ways.
private fun text(value: String): String = "${value.encodeToByteArray().size}:$value"

private const val HEX_DIGITS = 16
