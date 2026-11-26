// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * The starter catalog (spec 12, spec 23): original channel names, each with a one-line personality. A recipe is filled
 * later from whatever the library has, through genre, tag, studio and year-range sources and (from version 3) its
 * theme's anchors, with spelling variants listed as separate sources because scrapers disagree. Spread the numbers
 * out inside a band so later recipes can slot in between; a shipped key or number never changes and is never reused.
 * (One owner-approved exception, version 8: see there.)
 *
 * A recipe key is a persistence identifier: stored lineups, channel IDs and the planner's matching all hang on it. It
 * is frozen once released, and after release only a recipe's `name` and `personality` (its one-line tagline) may
 * change.
 */
object StarterCatalog : StarterCatalogView {
    /**
     * The catalog's version. It goes up with any change to what a recipe airs (sources, exclusions, collection hints,
     * ordering), its ceiling or opt-in, what it needs to qualify (`qualifyingBar`), or a recipe or leftovers recipe
     * added or removed. The frozen manifests in the tests hold the catalog to that.
     *
     * Version 2: every recipe outside the Kids band excludes every spelling of the kids' genres the kid hold knows
     * (`KidHold.KIDS_GENRES`), so kids' shows stop airing on grown-up channels.
     *
     * Version 3 (spec 23): the themed, programmed recipes ([CATALOG_V3_RECIPES]), every key, number, band, ceiling and
     * opt-in kept. A lineup moves to it from the next day boundary (the planner keeps today's rules, D1).
     *
     * Version 4 (the spread day end, ADR-0027): the recipes are unchanged. Catalog 4 means starters at rules format 5:
     * the bump makes the planner resolve every unedited starter again, now at 5 (`ChannelRules.newRulesWriteVersion`),
     * through its usual path: a starter on the air (a keeper) keeps today's rules and switches at the next boundary,
     * any other switches at once. Edited starters keep their version. Like any newer catalog it waits for a full
     * collection pass: until one applies it, a selecting lineup is in `PlannerSelection.Mode.Hold`, so no starter is
     * switched on or leaves in that window. The bump cannot be undone by a lower catalog: an older app freezes the
     * planner on a catalog 4 lineup (`LineupPlanner.plan`).
     *
     * Version 5 (the catalog gaps, #786): every key, number, band, ceiling and opt-in kept, and no rules format or hold
     * version changes. Cartoons After Dark takes the Animation genre (not Family); Laugh Track leaves out only the
     * genres that mean "not a sitcom" and Oddly Funny's twist genres (Oddly Funny is the channel to watch); the
     * single lanes of a niche theme carry a lower qualifying bar ([QualifyingBar.Niche]: 6 usable hours, 4 titles).
     * Unedited starters take it at the next boundary, as for any catalog update.
     *
     * Trade-off of the Family exclusion: an adult cartoon that reaches Cartoons After Dark through a tag or an anchor
     * (not the genre) AND carries the Family genre was on it at catalog 4 and is off it at catalog 5. It then falls to
     * another lane, or to Odds & Ends. That is accepted: Family is the signal that keeps PG family animation, which
     * the grown-up hold passes, off a channel for grown-ups (version 17 adds the adult signal for the genre entry, and
     * Family stays out as well). The audit counts the titles it affects per profile
     * library (`LineupAuditTest`).
     *
     * Version 6 (the kids recipes stop copying Saturday Morning, #591): every key, number, band and opt-in kept, no
     * rules format or hold version changes. Once Upon drops Fantasy, Backyard Fort and Giggle Garage drop the Kids
     * genre and the `children` tag, Robot Factory drops the Kids genre, Little Spirits drops
     * Animation, Little Farm adds
     * the Preschool and Pre-school genres, and Small Sleuths' ceiling goes from PG to TV-Y7 (still kid-safe, so
     * [EVER_KID_SAFE_KEYS] holds it). Unedited starters take it at the next boundary, as for any catalog update.
     *
     * Version 7 (recipe pack: TMDb / TVDb spellings, #994; General, Drama, Genre and World rescopes, #997 to #999; One
     * Season Only, #1000): sources, filters, years and one pitch only. Every key, number, band, opt-in flag and
     * ceiling is kept, no kids recipe changes, and no rules format or hold version changes. Renames, merges, cuts and
     * Oddly Funny's move wait for the channel marks.
     *
     * Version 8 (the young bands, #1002, ADR-0030): Kids 1 to 33, Family 34 to 66, Teen 67 to 99, each ending in its
     * own Odds & Ends (33, 66, 99). Thirteen kids channels take new Kids numbers; Once Upon, Rainy Day Matinee, Little
     * Spirits and Spooky Sleepover move to Family (PG ceiling, no longer kids-only, still held to PG by
     * [EVER_KID_SAFE_KEYS]); Kids' Odds & Ends moves from 99 to 33 and keeps a kid ceiling (PG); Family and Teen
     * leftovers cap at PG and TV-14. New: Big Screen Toons, Popcorn Matinee, Starship Sunday, Prime Time Pals (Family),
     * Homeroom, Mask & Mantle, Level Up, Popcorn Epic, Nervous Laughter (Teen). Lights Out, Tall Tales Club and Tiny
     * Chefs keep their numbers (44, 72, 80) and stay kids-only until #995 and #996 merge or cut them. Little Spirits
     * becomes anime-only (a Family channel that is not drops anime). Paper Lantern is dropped. No rules format or hold
     * version changes.
     *
     * NEVER-REUSE WAIVER (owner-approved, pre-release only): the rule above, a shipped key or number never changes and
     * is never reused, is waived once, for the renumbering of version 8 ("bypass the veto, this is a pre release
     * change", owner, 2026-11-19). The waiver covers only this move; the rule resumes after v1 and applies to
     * version 8's numbers as shipped. An installed lineup takes the new numbers at the next refresh
     * ([LineupPlanner]); the frozen manifests V1 to V7 stay as they were and `CatalogManifestTest` names the waived
     * keys.
     *
     * Version 9 (three merges, #995): Lights Out folds into Little Farm, Tall Tales Club into Backyard Fort and Haunted
     * House into The Midnight Projector. The three keys and their numbers (44, 72, 368) are retired: never reused, and
     * Lights Out and Tall Tales Club stay in [EVER_KID_SAFE_KEYS] so a stored copy keeps its kid hold. The three
     * survivors keep every key, number and recipe (Little Farm already takes the preschool tags and genres, Backyard
     * Fort has no Kids genre since catalog 6). No rules format or hold version changes.
     *
     * Version 10 (three cuts, #996): Tiny Chefs, Graveyard Shift and Director's Chair are removed. Their keys and
     * numbers (80, 508, 532) are retired and never reused. Tiny Chefs stays in [EVER_KID_SAFE_KEYS], so a stored copy
     * keeps its kid hold. Simmer keeps the cooking lane. Couch Talk already has no `late night` tag (catalog 7), so it
     * is unchanged. No rules format or hold version changes.
     *
     * Version 11 (five renames, #1004): Open Road is Riding Shotgun, Silver Screen is Golden Age Matinee, Rabbit Ears
     * is Vertical Hold, Kung Fu Theater is Fists & Fury and Comedy County is Oddly Funny, with new taglines for
     * three. No recipe, key, number or ceiling changes, so the manifest is as at 10; the version goes up so an
     * installed lineup is re-planned and its unedited starters take the new name (a viewer's own rename is kept). No
     * rules format or hold version changes.
     *
     * Version 12 (the seasonal band, #592): 900 to 999 is switched on ([Band.Seasonal]) with channel-level date-gating
     * ([Season], windows in [LineupDefaults]). Always December moves from 182 to 900 and Jack-o'-Lantern Lounge from
     * 340 to 910 (their old numbers are retired; the owner's pre-release waiver, as at version 8, named in
     * `NUMBERS_WAIVED_AT_12`), and keep their keys, recipes and marks. Snowglobe (Kids 20) and Spooky Sleepover (Family
     * 45) stay in place and take the same gating. A seasonal recipe's pool is empty out of season, so the planner
     * shelves it (reason Empty) and switches it on again by itself when the window opens. A viewer's own number and
     * choice are kept. No rules format or hold version changes.
     *
     * Version 13 (#1000): Oddly Funny (key `satire-desk`) moves from 528 in Premium to 190 in General, with a tagline
     * that matches its filter. Key, name, sources and filter are unchanged, so its fingerprint is too. The old number
     * (528) is retired and never reused: a named per-key waiver of the rule, owner-approved and pre-release as at
     * versions 8 and 12 (`NUMBERS_WAIVED_AT_13`, ADR-0030). One Season Only already has its Mini-Series genre and no
     * in-order promise (catalog 7), so it is unchanged. No rules format or hold version changes.
     *
     * Version 14 (#1078, with `kid-hold-8` / `grown-up-hold-7`): After Hours (key `after-hours`) gains the genres
     * Hentai, Porn, Pornography and Sexploitation and the tags hentai, pornography, porn, softcore, sexploitation and
     * erotic movie, so a custom copy of it keeps most of its reach; the starter itself airs every Explicit title
     * (`PoolPolicy.ExplicitOnly`). Its fingerprint changes; its key, number, band and opt-in flag are kept, and every
     * other entry is as it was at 13. No rules format change.
     *
     * Version 15 (#1103): the band names reach the leftovers channels. Keys, numbers, ceilings and stored ids are
     * unchanged; only the names move, to Tots' (33, key `kids-odds-and-ends`), Kids' (66) and Teens' (99) Odds & Ends.
     * No fingerprint, hold or rules format change.
     *
     * Version 16 (#1002): Rainy Day Matinee (key `rainy-day-matinee`, 39) loses its Films format promise, so the +80
     * fit points no longer let it out-home Popcorn Matinee, Big Screen Toons and Starship Sunday. It is still films
     * only (the Movie filter). Key, number, band, ceiling and opt-in are unchanged; its fingerprint changes. No rules
     * format or hold version change.
     *
     * Version 17 (#1154): Cartoons After Dark (key `cartoons-after-dark`, 516) needs an adult signal for a title that
     * comes in through the Animation genre alone: a decisive rating of PG-13 / TV-14 or higher. Kid-rated, Family-tier
     * and unrated animation with no Family genre no longer airs there; a title that reaches the channel through an
     * adult tag, a theme studio or an anchor is unchanged. Unrated titles still fail up to the Adult tier; they are
     * simply not admitted through the genre. The rule is the recipe's `Theme.adultSignalOnGenreEntry`, read by the
     * curation only: it is not in any channel's rules, the rules format or a stored file. Key, number, band, ceiling
     * and opt-in are unchanged; its fingerprint changes. No rules format or hold version change.
     *
     * Version 18 (#1031): Little Spirits (key `little-spirits`, 42) stops airing every PG anime. The bare Anime genre
     * and the generic `anime` tag are no longer its sources, so a long PG series (a mecha show) does not flood it. It
     * keeps the `family anime` tag and the Ghibli and Miyazaki collection anchors, and gains three theme studios
     * (Studio Ghibli, Studio Ponoc, GKIDS), so a family anime film that is in no collection still comes in. It stays
     * anime only, PG, Family band, number 42. Its fingerprint changes; every other entry is as it was at 17. No rules
     * format or hold version change.
     */
    const val VERSION = 18

    /** The catalog that moved the young bands: a lineup from before it has its numbers moved (`CatalogRenumbering`). */
    const val YOUNG_BANDS_VERSION = 8

    /** The catalog that switched on the seasonal band: a lineup from before it has its seasonal channels moved. */
    const val SEASONAL_VERSION = 12

    /** The catalog that moved Oddly Funny to General: a lineup from before it has that one channel moved. */
    const val ODDLY_FUNNY_VERSION = 13

    /** The key of Oddly Funny, the one starter that moves at [ODDLY_FUNNY_VERSION]. */
    const val ODDLY_FUNNY_KEY = "satire-desk"

    override val version: Int = VERSION

    override val recipes: List<Recipe> = CATALOG_V3_RECIPES

    /** The leftovers recipes. The kids' one, `kids-odds-and-ends`, always ships with a ceiling and is never retired. */
    override val leftovers: List<LeftoversRecipe> =
        listOf(
            LeftoversRecipe(
                key = "kids-odds-and-ends",
                name = "Tots' Odds & Ends",
                personality = "Whatever the other Tots' channels didn't take, still safe to watch.",
                number = 33,
                maxRating = "PG",
            ),
            LeftoversRecipe(
                key = "family-odds-and-ends",
                name = "Kids' Odds & Ends",
                personality = "Whatever the other Kids' channels didn't take, still fine for everyone.",
                number = 66,
                maxRating = "PG",
            ),
            LeftoversRecipe(
                key = "teen-odds-and-ends",
                name = "Teens' Odds & Ends",
                personality = "Whatever the other Teens' channels didn't take, nothing past PG-13.",
                number = 99,
                maxRating = "TV-14",
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
     * same kid keys as catalog 3; a key is added here when a later catalog retires a kid recipe (catalog 9: two,
     * catalog 10: one).
     * Declared before [EVER_KID_SAFE_KEYS], which reads it.
     */
    private val retiredKidSafeKeys: Set<String> = setOf("lights-out", "tall-tales-club", "tiny-chefs")

    /**
     * The keys that were kids-only until catalog 8 and are Family recipes now. They are still held to PG (they are in
     * [EVER_KID_SAFE_KEYS]) but are not kid channels: they air family titles, not the kids tier.
     */
    val KIDS_KEYS_NOW_FAMILY: Set<String> =
        setOf("once-upon", "rainy-day-matinee", "little-spirits", "spooky-sleepover")

    /**
     * Every recipe and leftovers key that has ever been kid-safe (a rating ceiling of PG or lower) in any released
     * catalog, retired or not. A stored channel with one of these keys is held to PG whatever its number or `edited`
     * flag, so renumbering it out of the Kids band, or retiring its recipe, never lifts its ceiling. Keys are only ever
     * added here, never removed; the catalog tests hold it to every frozen manifest. A Family or Teen key that is new
     * at catalog 8 is not in it.
     */
    val EVER_KID_SAFE_KEYS: Set<String> =
        (
            recipes.filter { it.kidSafe }.map { it.key } +
                KIDS_KEYS_NOW_FAMILY +
                retiredKidSafeKeys +
                leftovers.filter { it.number in Band.Kids.numbers }.map { it.key }
        ).toSet()

    override val retiredNumbers: Set<Int> = setOf(44, 72, 80, 182, 340, 368, 508, 528, 532)

    override val retiredKeys: Set<String> =
        setOf("lights-out", "tall-tales-club", "haunted-house", "tiny-chefs", "graveyard-shift", "directors-chair")

    /** The recipe with this key, or null. */
    fun recipe(key: String): Recipe? = recipes.firstOrNull { it.key == key }
}
