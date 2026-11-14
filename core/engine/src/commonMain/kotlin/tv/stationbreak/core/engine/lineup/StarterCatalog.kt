// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * The starter catalog (spec 12, spec 23): original channel names, each with a one-line personality. A recipe is filled
 * later from whatever the library has, through genre, tag, studio and year-range sources and (from version 3) its
 * theme's anchors, with spelling variants listed as separate sources because scrapers disagree. Spread the numbers
 * out inside a band so later recipes can slot in between; a shipped key or number never changes and is never reused.
 *
 * A recipe key is a persistence identifier: stored lineups, channel IDs and the planner's matching all hang on it. It
 * is frozen once released, and after release only a recipe's `name` and `personality` (its one-line tagline) may
 * change.
 */
object StarterCatalog : StarterCatalogView {
    /**
     * The catalog's version. It goes up with any change to what a recipe airs (sources, exclusions, collection hints,
     * ordering), its ceiling or opt-in, or a recipe or leftovers recipe added or removed. The frozen manifests in the
     * tests hold the catalog to that.
     *
     * Version 2: every recipe outside the Kids band excludes every spelling of the kids' genres the kid hold knows
     * (`KidHold.KIDS_GENRES`), so kids' shows stop airing on grown-up channels.
     *
     * Version 3 (spec 23): the themed, programmed recipes ([CATALOG_V3_RECIPES]), every key, number, band, ceiling and
     * opt-in kept. A lineup moves to it from the next day boundary (the planner keeps today's rules, D1).
     *
     * Version 4 (the spread day end, ADR-0027): the recipes are unchanged. The bump makes the planner resolve every
     * unedited starter again, now at rules format 5, through its usual path: a starter on the air (a keeper) keeps
     * today's rules and switches at the next boundary, any other switches at once. Edited starters keep their version.
     */
    const val VERSION = 4

    override val version: Int = VERSION

    /** The first catalog version whose starters the planner selects by identity (spec 23). */
    const val SELECTS_BY_IDENTITY_FROM: Int = 3

    override val selectsByIdentity: Boolean = VERSION >= SELECTS_BY_IDENTITY_FROM

    override val recipes: List<Recipe> = CATALOG_V3_RECIPES

    /** The leftovers recipes. The kids' one, `kids-odds-and-ends`, always ships with a ceiling and is never retired. */
    override val leftovers: List<LeftoversRecipe> =
        listOf(
            LeftoversRecipe(
                key = "kids-odds-and-ends",
                name = "Kids' Odds & Ends",
                personality = "Whatever the other kids' channels didn't take, still safe to watch.",
                number = 99,
                maxRating = "PG",
            ),
            LeftoversRecipe(
                key = "odds-and-ends",
                name = "Odds & Ends",
                personality = "Whatever the other channels didn't take. Expect surprises.",
                number = 199,
                maxRating = null,
            ),
        )

    /**
     * The kid-safe keys of earlier catalogs that this one no longer has as kid-safe recipes. Catalogs 1 and 2 had the
     * same kid keys as catalog 3, so it is empty; a key is added here when a later catalog retires a kid recipe.
     * Declared before [EVER_KID_SAFE_KEYS], which reads it.
     */
    private val retiredKidSafeKeys: Set<String> = emptySet()

    /**
     * Every recipe and leftovers key that has ever been kid-safe (a rating ceiling of PG or lower) in any released
     * catalog, retired or not. A stored channel with one of these keys is held to PG whatever its number or `edited`
     * flag, so renumbering it out of the Kids band, or retiring its recipe, never lifts its ceiling. Keys are only ever
     * added here, never removed; the catalog tests hold it to every frozen manifest.
     */
    val EVER_KID_SAFE_KEYS: Set<String> =
        (
            recipes.filter { it.kidSafe }.map { it.key } +
                retiredKidSafeKeys +
                leftovers.filter { it.maxRating != null }.map { it.key }
        ).toSet()

    override val retiredNumbers: Set<Int> = emptySet()

    override val retiredKeys: Set<String> = emptySet()

    /** The recipe with this key, or null. */
    fun recipe(key: String): Recipe? = recipes.firstOrNull { it.key == key }
}
