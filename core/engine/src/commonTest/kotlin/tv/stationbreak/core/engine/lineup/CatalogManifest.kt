// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

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
        }
    return fnv1a64(canonical)
}

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
    val recipes = catalog.recipes.associateBy { it.key }
    val leftovers = catalog.leftovers.associateBy { it.key }
    for ((key, shipped) in manifest.recipes) {
        val recipe = recipes[key]
        if (recipe == null) {
            problems += retirementProblems(catalog, key, shipped.number)
        } else if (recipe.number != shipped.number || recipe.band != shipped.band) {
            problems += "$key moved from ${shipped.number} (${shipped.band}) to ${recipe.number} (${recipe.band})"
        }
    }
    for ((key, shipped) in manifest.leftovers) {
        val recipe = leftovers[key]
        if (recipe == null) {
            problems += retirementProblems(catalog, key, shipped.number)
        } else if (recipe.number != shipped.number) {
            problems += "leftovers $key moved from ${shipped.number} to ${recipe.number}"
        }
    }
    if (catalog.version <= manifest.version) {
        val now = catalog.recipes.associate { it.key to ManifestRecipe.of(it) }
        val changed = (now.keys + manifest.recipes.keys).filter { now[it] != manifest.recipes[it] }.sorted()
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
        }
    return "$body;"
}

// A text with its UTF-8 byte length in front, so the canonical string cannot be read two ways.
private fun text(value: String): String = "${value.encodeToByteArray().size}:$value"

private const val FNV_OFFSET: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x100000001b3uL
private const val HEX_DIGITS = 16
private const val BYTE_MASK = 0xFF

private fun fnv1a64(value: String): String {
    var hash = FNV_OFFSET
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toInt() and BYTE_MASK).toULong()
        hash *= FNV_PRIME
    }
    return hash.toString(HEX_DIGITS).padStart(HEX_DIGITS, '0')
}
