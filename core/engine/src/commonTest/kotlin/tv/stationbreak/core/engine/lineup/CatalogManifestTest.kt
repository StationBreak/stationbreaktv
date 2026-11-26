// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shipped catalog keeps the promises frozen in every manifest of [CATALOG_MANIFESTS]. */
class CatalogManifestTest {
    private val shipped = StarterCatalog.recipes
    private val first = shipped.first()

    // A plain recipe (catalog 2's first): one whose hints and ordering a test may change, which a themed one refuses.
    private val plainFirst = CATALOG_V2_RECIPES.first()

    private val moviesOnly = Filters(null, null, null, null, null, FilterKind.Movie)

    private fun catalog(
        recipes: List<Recipe> = shipped,
        version: Int = 25,
        leftovers: List<LeftoversRecipe> = StarterCatalog.leftovers,
        retiredKeys: Set<String> = emptySet(),
        retiredNumbers: Set<Int> = emptySet(),
    ) = TestCatalog(recipes, version, leftovers, retiredNumbers, retiredKeys)

    private fun withFirst(change: (Recipe) -> Recipe) = shipped.map { if (it == first) change(it) else it }

    private fun problems(catalog: StarterCatalogView) = manifestProblems(catalog, CATALOG_MANIFEST_V25)

    @Test
    fun theShippedCatalogKeepsEveryManifest() {
        for (manifest in CATALOG_MANIFESTS) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "manifest v${manifest.version}")
        }
        val versions = CATALOG_MANIFESTS.map { it.version }
        assertEquals(versions.sorted().distinct(), versions, "manifests are oldest first, one per version")
        assertEquals(StarterCatalog.VERSION, CATALOG_MANIFESTS.last().version, "the current version is frozen too")
        assertEquals(StarterCatalog.recipes.size, CATALOG_MANIFEST_V25.recipes.size)
        assertEquals(1, CATALOG_MANIFEST_V1.version)
        assertEquals(2, CATALOG_MANIFEST_V2.version)
        assertEquals(3, CATALOG_MANIFEST_V3.version)
        assertEquals(4, CATALOG_MANIFEST_V4.version)
        assertEquals(5, CATALOG_MANIFEST_V5.version)
        assertEquals(6, CATALOG_MANIFEST_V6.version)
        assertEquals(7, CATALOG_MANIFEST_V7.version)
        assertEquals(8, CATALOG_MANIFEST_V8.version)
        assertEquals(9, CATALOG_MANIFEST_V9.version)
        assertEquals(10, CATALOG_MANIFEST_V10.version)
        assertEquals(11, CATALOG_MANIFEST_V11.version)
        assertEquals(12, CATALOG_MANIFEST_V12.version)
        assertEquals(13, CATALOG_MANIFEST_V13.version)
        assertEquals(14, CATALOG_MANIFEST_V14.version)
        assertEquals(15, CATALOG_MANIFEST_V15.version)
        assertEquals(16, CATALOG_MANIFEST_V16.version)
        assertEquals(17, CATALOG_MANIFEST_V17.version)
        assertEquals(18, CATALOG_MANIFEST_V18.version)
        assertEquals(19, CATALOG_MANIFEST_V19.version)
        assertEquals(20, CATALOG_MANIFEST_V20.version)
        assertEquals(21, CATALOG_MANIFEST_V21.version)
        assertEquals(22, CATALOG_MANIFEST_V22.version)
        assertEquals(23, CATALOG_MANIFEST_V23.version)
        assertEquals(24, CATALOG_MANIFEST_V24.version)
        assertEquals(25, CATALOG_MANIFEST_V25.version)
    }

    // Catalog 8 (the young bands, #1002) changes no fingerprint: every recipe of catalog 7 airs what it aired. It moves
    // the 17 recipes and the leftovers recipe of NUMBERS_WAIVED_AT_8 (the owner's pre-release waiver of the never-reuse
    // rule), and adds nine recipes and two leftovers recipes; nothing else.
    @Test
    fun versionEightMovesOnlyTheWaivedNumbersAndAddsTheNewChannels() {
        val before = CATALOG_MANIFEST_V7.recipes
        val after = CATALOG_MANIFEST_V8.recipes
        assertEquals(before.keys, after.keys - NEW_AT_8)
        assertEquals(NEW_AT_8, after.keys - before.keys)
        for ((key, old) in before) {
            val now = after.getValue(key)
            // Little Spirits is the one recipe that changes: it is anime-only, as Family is not the Kids band.
            assertEquals(key != "little-spirits", old.fingerprint == now.fingerprint, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            assertEquals(NUMBERS_WAIVED_AT_8[key] ?: old.number, now.number, key)
        }
        // Lights Out keeps its number (44) but is in the Family band by it; it is still a kids-only recipe.
        val family = setOf("once-upon", "rainy-day-matinee", "little-spirits", "lights-out", "spooky-sleepover")
        assertEquals(family, movedToFamily())
        assertEquals(
            setOf("kids-odds-and-ends", "family-odds-and-ends", "teen-odds-and-ends"),
            CATALOG_MANIFEST_V8.leftovers.keys - CATALOG_MANIFEST_V7.leftovers.keys + "kids-odds-and-ends",
        )
        assertEquals(33, CATALOG_MANIFEST_V8.leftovers.getValue("kids-odds-and-ends").number)
        assertEquals("PG", CATALOG_MANIFEST_V8.leftovers.getValue("family-odds-and-ends").maxRating)
        assertEquals("TV-14", CATALOG_MANIFEST_V8.leftovers.getValue("teen-odds-and-ends").maxRating)
        assertEquals(199, CATALOG_MANIFEST_V8.leftovers.getValue("odds-and-ends").number)
    }

    private fun movedToFamily() =
        CATALOG_MANIFEST_V8.recipes
            .filter { (k, v) -> v.band == Band.Family && CATALOG_MANIFEST_V7.recipes[k]?.band == Band.Kids }
            .keys

    // The waiver is named, per key. A move of any other key, or to any other number, is still a problem at catalog 8.
    @Test
    fun theWaiverCoversOnlyTheNamedKeysAtTheirNewNumbers() {
        for (manifest in CATALOG_MANIFESTS.filter { it.version < 8 }) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "v${manifest.version}")
            val other = shipped.map { if (it.key == "critter-channel") it.copy(number = 11) else it }
            assertTrue(manifestProblems(catalog(other), manifest).isNotEmpty(), "a number outside the waiver")
            val grownUp = shipped.map { if (it.key == "prestige") it.copy(number = 205) else it }
            assertTrue(manifestProblems(catalog(grownUp), manifest).isNotEmpty(), "a key outside the waiver")
        }
        assertEquals(NUMBERS_WAIVED_AT_8.size, 18)
        val old = shipped.map { if (it.key == "critter-channel") it.copy(number = 24) else it }
        assertTrue(manifestProblems(catalog(old, version = 7), CATALOG_MANIFEST_V7).isNotEmpty(), "needs the version")
    }

    // Catalog 9 (three merges, #995) drops three recipes and keeps every other entry exactly: same number, band,
    // opt-in flag, ceiling and fingerprint. The three keys and their numbers are retired, never reused.
    @Test
    fun versionNineDropsOnlyTheThreeMergedRecipesAndRetiresThem() {
        val merged = setOf("lights-out", "tall-tales-club", "haunted-house")
        assertEquals(CATALOG_MANIFEST_V8.recipes.keys - merged, CATALOG_MANIFEST_V9.recipes.keys)
        for ((key, now) in CATALOG_MANIFEST_V9.recipes) assertEquals(CATALOG_MANIFEST_V8.recipes[key], now, key)
        assertEquals(CATALOG_MANIFEST_V8.leftovers, CATALOG_MANIFEST_V9.leftovers)
        assertTrue(StarterCatalog.retiredKeys.containsAll(merged))
        assertEquals(setOf(44, 72, 368), merged.map { CATALOG_MANIFEST_V8.recipes.getValue(it).number }.toSet())
        // The survivors stay, and a retired kid key keeps its kid hold.
        for (key in listOf("little-farm", "backyard-fort", "midnight-projector")) {
            assertNotNull(StarterCatalog.recipe(key))
        }
        assertTrue("lights-out" in StarterCatalog.EVER_KID_SAFE_KEYS)
        assertTrue("tall-tales-club" in StarterCatalog.EVER_KID_SAFE_KEYS)
    }

    // Catalog 10 (three cuts, #996) drops three recipes and keeps every other entry exactly. The three keys and their
    // numbers are retired, never reused; Tiny Chefs, a kid recipe, keeps its kid hold. Couch Talk is not touched.
    @Test
    fun versionTenDropsOnlyTheThreeCutRecipesAndRetiresThem() {
        val cut = setOf("tiny-chefs", "graveyard-shift", "directors-chair")
        assertEquals(CATALOG_MANIFEST_V9.recipes.keys - cut, CATALOG_MANIFEST_V10.recipes.keys)
        for ((key, now) in CATALOG_MANIFEST_V10.recipes) assertEquals(CATALOG_MANIFEST_V9.recipes[key], now, key)
        assertEquals(CATALOG_MANIFEST_V9.leftovers, CATALOG_MANIFEST_V10.leftovers)
        assertEquals(
            setOf("lights-out", "tall-tales-club", "haunted-house") + cut,
            StarterCatalog.retiredKeys,
        )
        assertTrue(StarterCatalog.retiredNumbers.containsAll(setOf(44, 72, 80, 368, 508, 532)))
        assertEquals(setOf(80, 508, 532), cut.map { CATALOG_MANIFEST_V9.recipes.getValue(it).number }.toSet())
        assertTrue("tiny-chefs" in StarterCatalog.EVER_KID_SAFE_KEYS)
        assertTrue(StarterCatalog.recipes.none { it.key in cut })
        assertTrue(Source.Tag("late night") !in StarterCatalog.recipe("couch-talk")!!.sources)
    }

    // Catalog 11 (five renames, #1004) changes no manifest entry: a name and a tagline are not in it. The keys stay, so
    // marks (keyed by number and key) and every stored lineup stay valid.
    @Test
    fun versionElevenChangesNoManifestEntry() {
        assertEquals(CATALOG_MANIFEST_V10.recipes, CATALOG_MANIFEST_V11.recipes)
        assertEquals(CATALOG_MANIFEST_V10.leftovers, CATALOG_MANIFEST_V11.leftovers)
        val renamed =
            mapOf(
                "rabbit-ears" to "Vertical Hold",
                "open-road" to "Riding Shotgun",
                "silver-screen" to "Golden Age Matinee",
                "kung-fu-theater" to "Fists & Fury",
                "satire-desk" to "Oddly Funny",
            )
        for ((key, name) in renamed) assertEquals(name, StarterCatalog.recipe(key)?.name, key)
    }

    // Catalog 12 (the seasonal band, #592) moves the two holiday channels into 900 to 999 (named waiver, old numbers
    // retired) and gives four recipes their season; every other entry is as it was at 11.
    @Test
    fun versionTwelveMovesOnlyTheTwoHolidayChannelsAndSeasonsFour() {
        val before = CATALOG_MANIFEST_V11.recipes
        val after = CATALOG_MANIFEST_V12.recipes
        assertEquals(before.keys, after.keys)
        assertEquals(CATALOG_MANIFEST_V11.leftovers, CATALOG_MANIFEST_V12.leftovers)
        val seasonal = setOf("snowglobe", "spooky-sleepover", "always-december", "jack-o-lantern-lounge")
        for ((key, old) in before) {
            val now = after.getValue(key)
            assertEquals(key in seasonal, old.fingerprint != now.fingerprint, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            assertEquals(NUMBERS_WAIVED_AT_12[key] ?: old.number, now.number, key)
        }
        assertEquals(
            seasonal,
            StarterCatalog.recipes
                .filter { it.season != null }
                .map { it.key }
                .toSet(),
        )
        assertEquals(setOf("always-december", "jack-o-lantern-lounge"), NUMBERS_WAIVED_AT_12.keys)
        assertTrue(StarterCatalog.retiredNumbers.containsAll(setOf(182, 340)))
        assertTrue(StarterCatalog.recipes.none { it.number == 182 || it.number == 340 })
        assertEquals(Band.Seasonal, after.getValue("always-december").band)
        assertEquals(Band.Seasonal, after.getValue("jack-o-lantern-lounge").band)
        // The waiver is per key and per number: another number, or another key, is still a problem.
        val other = shipped.map { if (it.key == "always-december") it.copy(number = 901) else it }
        assertTrue(manifestProblems(catalog(other), CATALOG_MANIFEST_V11).isNotEmpty())
        val outside = shipped.map { if (it.key == "prestige") it.copy(number = 205) else it }
        assertTrue(manifestProblems(catalog(outside), CATALOG_MANIFEST_V11).isNotEmpty())
        assertEquals(emptyList(), manifestProblems(StarterCatalog, CATALOG_MANIFEST_V11))
    }

    // Catalog 13 (#1000) moves Oddly Funny from Premium 528 to General 190 (named waiver, old number retired); every
    // other entry, and every fingerprint, is as it was at 12.
    @Test
    fun versionThirteenMovesOnlyOddlyFunny() {
        val before = CATALOG_MANIFEST_V12.recipes
        val after = CATALOG_MANIFEST_V13.recipes
        assertEquals(before.keys, after.keys)
        assertEquals(CATALOG_MANIFEST_V12.leftovers, CATALOG_MANIFEST_V13.leftovers)
        for ((key, old) in before) {
            val now = after.getValue(key)
            assertEquals(old.fingerprint, now.fingerprint, key)
            assertEquals(NUMBERS_WAIVED_AT_13[key] ?: old.number, now.number, key)
            assertEquals(key == "satire-desk", old.number != now.number, key)
        }
        assertEquals(setOf("satire-desk"), NUMBERS_WAIVED_AT_13.keys)
        assertEquals(Band.General, after.getValue("satire-desk").band)
        assertEquals(190, StarterCatalog.recipe("satire-desk")?.number)
        assertTrue(528 in StarterCatalog.retiredNumbers)
        assertTrue(StarterCatalog.recipes.none { it.number == 528 })
        // The waiver is per key and per number: another number, or another key, is still a problem.
        val other = shipped.map { if (it.key == "satire-desk") it.copy(number = 191) else it }
        assertTrue(manifestProblems(catalog(other), CATALOG_MANIFEST_V12).isNotEmpty())
        val outside = shipped.map { if (it.key == "prestige") it.copy(number = 205) else it }
        assertTrue(manifestProblems(catalog(outside), CATALOG_MANIFEST_V12).isNotEmpty())
        for (manifest in CATALOG_MANIFESTS.filter { it.version < 13 }) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "V${manifest.version}")
        }
    }

    // Catalog 14 (#1078) changes only After Hours' fingerprint (its new explicit genres and tags); every key, number,
    // band, opt-in flag, ceiling and leftovers entry, and every other fingerprint, is as it was at 13.
    @Test
    fun versionFourteenChangesOnlyAfterHours() {
        val before = CATALOG_MANIFEST_V13.recipes
        val after = CATALOG_MANIFEST_V14.recipes
        assertEquals(before.keys, after.keys)
        assertEquals(CATALOG_MANIFEST_V13.leftovers, CATALOG_MANIFEST_V14.leftovers)
        for ((key, old) in before) {
            val now = after.getValue(key)
            assertEquals(old.copy(fingerprint = now.fingerprint), now, key)
            assertEquals(key == "after-hours", old.fingerprint != now.fingerprint, key)
        }
        val sources = StarterCatalog.recipe("after-hours")!!.sources
        val added =
            listOf("Hentai", "Porn", "Pornography", "Sexploitation").map { Source.Genre(it) } +
                listOf("hentai", "pornography", "porn", "softcore", "sexploitation", "erotic movie")
                    .map { Source.Tag(it) }
        assertTrue(added.all { it in sources })
        for (manifest in CATALOG_MANIFESTS.filter { it.version < 14 }) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "V${manifest.version}")
        }
    }

    @Test
    fun theSeasonIsInTheFingerprintOnlyWhenARecipeHasOne() {
        val grownUp = CATALOG_V2_RECIPES.first { it.maxRating == null }
        val plain = fingerprintOf(grownUp)
        assertNotEquals(plain, fingerprintOf(grownUp.copy(season = Season.Christmas)))
        assertNotEquals(
            fingerprintOf(grownUp.copy(season = Season.Christmas)),
            fingerprintOf(grownUp.copy(season = Season.Halloween)),
        )
    }

    // Catalog 7 (the recipe pack: #994, #997 to #999, #1000) keeps every key, number, band, opt-in flag, ceiling and
    // the leftovers, and changes no kids recipe. It changes the sources or filters of the recipes named here.
    @Test
    fun versionSevenChangesOnlyTheRecipesThePackNames() {
        assertEquals(CATALOG_MANIFEST_V6.recipes.keys, CATALOG_MANIFEST_V7.recipes.keys)
        assertEquals(CATALOG_MANIFEST_V6.leftovers, CATALOG_MANIFEST_V7.leftovers)
        val before = CATALOG_MANIFEST_V6.recipes
        val after = CATALOG_MANIFEST_V7.recipes
        for ((key, old) in before) {
            val now = after.getValue(key)
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
        }
        val changed = before.keys.filter { before.getValue(it).fingerprint != after.getValue(it).fingerprint }
        assertEquals(PACK_AT_7, changed.toSet())
        assertTrue(changed.none { before.getValue(it).band == Band.Kids }, "no kids recipe changes at 7")
    }

    // The spellings and sources the pack adds, pinned. Each old string stays where the issue said to keep it.
    @Test
    fun thePackRecipesHaveTheirNewSources() {
        fun recipe(key: String) = StarterCatalog.recipe(key)!!

        fun sources(key: String) = recipe(key).sources

        fun g(vararg n: String) = n.map { Source.Genre(it) }

        fun t(vararg n: String) = n.map { Source.Tag(it) }
        assertTrue(t("stand-up", "stand-up comedy").all { it in sources("punchline") })
        assertTrue(t("based on a true story", "based on true story").all { it in sources("life-stories") })
        assertTrue(g("Biography").all { it in sources("life-stories") })
        assertEquals(t("post-apocalyptic", "post-apocalyptic future", "apocalypse", "wasteland"), sources("end-times"))
        assertEquals(t("disaster", "natural disaster"), sources("disaster-zone"))
        assertTrue(t("lawyers", "lawyer").all { it in sources("order-in-the-court") })
        assertTrue(t("b-movie", "b movie").all { it in sources("drive-in-double") })
        assertTrue(Source.Genre("Mini-Series") in sources("one-season-only"))
        assertTrue(!recipe("one-season-only").personality.contains("in order"))
        assertTrue(g("Science Fiction", "Sci-Fi").all { it in sources("starport") })
        assertTrue(Source.Genre("Game Show") in sources("game-night"))
        assertEquals(g("Talk", "Talk Show") + t("talk show", "chat show"), sources("couch-talk"))
        assertEquals(t("soap opera"), sources("matinee-tears").filterIsInstance<Source.Tag>())
        assertTrue(Source.Genre("Sport") in sources("sideline"))
        assertTrue(Source.Genre("Travel") in sources("open-road"))
        assertTrue(Source.Genre("Food") in sources("simmer"))
        assertTrue(Source.Genre("Home and Garden") in sources("fixer-hour"))
        assertTrue(Source.Genre("Musical") in sources("curtain-call"))
        assertEquals(t("spy", "heist", "espionage"), sources("dead-drop"))
        assertTrue(recipe("dead-drop").filters!!.genres!!.contains("Thriller"))
        assertEquals(listOf("Documentary"), recipe("docs-and-dusters").filters!!.genres)
        assertTrue(Source.Genre("History") !in sources("docs-and-dusters"))
        assertTrue(Source.Genre("War & Politics") !in sources("front-lines"))
        assertTrue(Source.Genre("Film-Noir") in sources("noir-nights"))
        assertTrue(Source.Tag("neo-noir") !in sources("neon-rain"))
        assertTrue(Source.Genre("Martial Arts") in sources("kung-fu-theater"))
        assertTrue(Source.Tag("samurai") in sources("kung-fu-theater"))
        val silver = sources("silver-screen").filterIsInstance<Source.YearRange>().single()
        assertEquals(1920 to 1949, silver.from to silver.to)
    }

    // Whodunit Isles is Tea Time's mystery lane (#999). Continental Drift's first studio list stays short.
    @Test
    fun theWorldRecipesTakeTheirStudios() {
        val tea = StarterCatalog.recipe("tea-time")!!.theme!!.themeStudios
        val whodunit = StarterCatalog.recipe("whodunit-isles")!!
        assertEquals(tea, whodunit.theme!!.themeStudios)
        assertEquals(listOf("Mystery", "Crime"), whodunit.filters!!.genres)
        for (key in listOf("continental-drift", "seoul-sundays", "masala-hour", "telenovela-tonight")) {
            assertTrue(
                StarterCatalog
                    .recipe(key)!!
                    .theme!!
                    .themeStudios
                    .isNotEmpty(),
                key,
            )
        }
        assertTrue(
            StarterCatalog
                .recipe("continental-drift")!!
                .theme!!
                .themeStudios.size <= 8,
        )
        assertEquals(
            5,
            StarterCatalog
                .recipe("seoul-sundays")!!
                .theme!!
                .themeStudios.size,
        )
        assertEquals(
            4,
            StarterCatalog
                .recipe("telenovela-tonight")!!
                .theme!!
                .themeStudios.size,
        )
    }

    // Catalog 6 (#591) keeps every key, number, band and opt-in flag and the leftovers. It changes what six kids
    // recipes air and one kids ceiling (Small Sleuths, PG to TV-Y7); nothing else moves.
    @Test
    fun versionSixChangesOnlyTheKidsRecipesIssue591Names() {
        assertEquals(CATALOG_MANIFEST_V5.recipes.keys, CATALOG_MANIFEST_V6.recipes.keys)
        assertEquals(CATALOG_MANIFEST_V5.leftovers, CATALOG_MANIFEST_V6.leftovers)
        val before = CATALOG_MANIFEST_V5.recipes
        val after = CATALOG_MANIFEST_V6.recipes
        for ((key, old) in before) {
            val now = after.getValue(key)
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            if (key != "small-sleuths") assertEquals(old.maxRating, now.maxRating, key)
        }
        assertEquals("PG", before.getValue("small-sleuths").maxRating)
        assertEquals("TV-Y7", after.getValue("small-sleuths").maxRating)
        val changed = before.keys.filter { before.getValue(it).fingerprint != after.getValue(it).fingerprint }
        val named =
            setOf("once-upon", "backyard-fort", "robot-factory", "giggle-garage", "little-spirits", "little-farm")
        assertEquals(named, changed.toSet())
    }

    // The new source lists, pinned (Saturday Morning is not in them: it stays the flagship).
    @Test
    fun theKidsRecipesTightenedAtVersionSixHaveTheirNewSources() {
        fun sources(key: String) = StarterCatalog.recipe(key)!!.sources

        fun g(vararg n: String) = n.map { Source.Genre(it) }

        fun t(vararg n: String) = n.map { Source.Tag(it) }
        assertEquals(t("fairy tale", "fairy-tale", "folk tale"), sources("once-upon"))
        assertEquals(g("Adventure", "Action & Adventure") + t("adventure"), sources("backyard-fort"))
        val robots = g("Science Fiction", "Sci-Fi & Fantasy") + t("robots", "space", "sci-fi")
        assertEquals(robots, sources("robot-factory"))
        assertEquals(g("Comedy") + t("silly", "slapstick"), sources("giggle-garage"))
        assertEquals(t("family anime"), sources("little-spirits"))
        assertEquals(g("Preschool", "Pre-school") + t("preschool", "toddler"), sources("little-farm"))
        assertEquals("TV-Y7", StarterCatalog.recipe("small-sleuths")!!.maxRating)
        assertEquals(g("Animation", "Kids") + t("cartoon", "shorts", "children"), sources("saturday-morning"))
    }

    // Catalog 5 (#786, the catalog gaps) keeps every key, number, band, opt-in flag and ceiling and the leftovers. It
    // changes what exactly the recipes named below air or need: Cartoons After Dark (the Animation genre, not Family),
    // Laugh Track (its narrower notGenres) and the single lanes of a niche theme (a lower qualifying bar).
    @Test
    fun versionFiveChangesOnlyTheRecipesTheCatalogGapsNames() {
        assertEquals(CATALOG_MANIFEST_V4.recipes.keys, CATALOG_MANIFEST_V5.recipes.keys)
        assertEquals(CATALOG_MANIFEST_V4.leftovers, CATALOG_MANIFEST_V5.leftovers)
        for ((key, old) in CATALOG_MANIFEST_V4.recipes) {
            val now = CATALOG_MANIFEST_V5.recipes.getValue(key)
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
        }
        val before = CATALOG_MANIFEST_V4.recipes
        val after = CATALOG_MANIFEST_V5.recipes
        val changed = before.keys.filter { before.getValue(it).fingerprint != after.getValue(it).fingerprint }
        val named =
            setOf("cartoons-after-dark", "laugh-track") +
                StarterCatalog.recipes.filter { it.qualifyingBar == QualifyingBar.Niche }.map { it.key }
        assertEquals(named, changed.toSet())
    }

    // The bar joins the fingerprint only when it is not the standard one, so every earlier manifest still holds.
    @Test
    fun theQualifyingBarIsInTheFingerprintOnlyWhenItIsNotTheStandardOne() {
        val grownUp = CATALOG_V2_RECIPES.first { it.maxRating == null }
        val standard = fingerprintOf(grownUp)
        assertEquals(standard, fingerprintOf(grownUp.copy(qualifyingBar = QualifyingBar.Standard)))
        assertNotEquals(standard, fingerprintOf(grownUp.copy(qualifyingBar = QualifyingBar.Niche)))
    }

    // Catalog 4 (the spread day end) changed no recipe and no leftovers: the bump only moves starters to format 5.
    @Test
    fun versionFourIsVersionThreeEntryForEntry() {
        assertEquals(CATALOG_MANIFEST_V3.recipes, CATALOG_MANIFEST_V4.recipes)
        assertEquals(CATALOG_MANIFEST_V3.leftovers, CATALOG_MANIFEST_V4.leftovers)
    }

    // The catalog swap (spec 23) keeps every promise but what the recipes air: the same keys, numbers, bands, opt-in
    // flags and ceilings, and the same leftovers, so no ceiling a channel is scheduled under moves at the swap.
    @Test
    fun versionThreeKeepsEveryKeyNumberBandAndCeiling() {
        assertEquals(CATALOG_MANIFEST_V2.recipes.keys, CATALOG_MANIFEST_V3.recipes.keys)
        for ((key, old) in CATALOG_MANIFEST_V2.recipes) {
            val now = CATALOG_MANIFEST_V3.recipes.getValue(key)
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            // Every recipe is themed and programmed now, so each airs differently.
            assertNotEquals(old.fingerprint, now.fingerprint, key)
        }
        assertEquals(CATALOG_MANIFEST_V2.leftovers, CATALOG_MANIFEST_V3.leftovers)
        // Catalog 2's recipes as kept in the code are still what manifest V2 froze.
        val v2 = TestCatalog(CATALOG_V2_RECIPES, version = 2, leftovers = CATALOG_V2_LEFTOVERS)
        assertEquals(emptyList(), manifestProblems(v2, CATALOG_MANIFEST_V2))
    }

    @Test
    fun versionTwoKeepsEveryVersionOneKeyNumberAndBand() {
        for ((key, old) in CATALOG_MANIFEST_V1.recipes) {
            val now = CATALOG_MANIFEST_V2.recipes[key]
            assertNotNull(now, "$key kept in v2")
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            // Only the grown-up recipes changed what they air.
            assertEquals(old.band == Band.Kids, old.fingerprint == now.fingerprint, key)
        }
        assertEquals(CATALOG_MANIFEST_V1.leftovers, CATALOG_MANIFEST_V2.leftovers)
    }

    // Catalog 2's promise (catalog 3's is in CatalogV3Test, with the anime exclusions added).
    @Test
    fun everyGrownUpRecipeExcludesTheKidsGenresAndKidRecipesKeepTheirSources() {
        // Exactly the spellings the kid hold recognises, so a grown-up channel drops what a kid channel would take.
        val kidsGenres = KidHold.KIDS_GENRES.map { Source.Genre(it) }
        for (recipe in CATALOG_V2_RECIPES) {
            if (recipe.maxRating != null) {
                assertEquals(emptyList(), recipe.exclusions, recipe.key)
            } else {
                assertEquals(kidsGenres, recipe.exclusions, recipe.key)
            }
        }
        // A kid recipe of catalog 2 still fingerprints as it did at version 1, tags included.
        for ((key, old) in CATALOG_MANIFEST_V1.recipes.filterValues { it.band == Band.Kids }) {
            assertEquals(old.fingerprint, fingerprintOf(CATALOG_V2_RECIPES.first { it.key == key }), key)
        }
        assertTrue(CATALOG_V2_RECIPES.first { it.key == "little-farm" }.sources.any { it is Source.Tag })
    }

    @Test
    fun catalogSourcesCarryNoExtras() {
        for (recipe in StarterCatalog.recipes) {
            assertTrue((recipe.sources + recipe.exclusions).all { it.extras.isEmpty() }, recipe.key)
        }
    }

    @Test
    fun theKidsLeftoversKeyIsAlwaysShippedWithACeiling() {
        val kids = StarterCatalog.leftovers.single { it.key == "kids-odds-and-ends" }
        assertNotNull(kids.maxRating)
        assertTrue("kids-odds-and-ends" !in StarterCatalog.retiredKeys)
        CATALOG_MANIFESTS.forEach { assertNotNull(it.leftovers.getValue("kids-odds-and-ends").maxRating) }
    }

    @Test
    fun theFingerprintIsAFixedFunctionOfTheCanonicalText() {
        // Worked out independently of this code, so the JVM and linuxX64 must both give exactly these.
        val sources = listOf(Source.Genre("Sci-Fi"), Source.Tag("café"), Source.YearRange(1980, 1989))
        val recipe = recipeOf("alpha", 110, hints = listOf("Kaiju"), sources = sources)
        assertEquals("a31d4dbdbabab50a", fingerprintOf(recipe))
        assertEquals("23dbb92882d87475", fingerprintOf(recipe.copy(ordering = OrderingMode.Shuffle)))
    }

    @Test
    fun whatARecipeAirsIsInTheFingerprint() {
        val base = fingerprintOf(plainFirst)
        val changes =
            listOf(
                plainFirst.copy(sources = plainFirst.sources + Source.Genre("Extra")),
                plainFirst.copy(sources = plainFirst.sources.reversed()),
                plainFirst.copy(exclusions = listOf(Source.Tag("scary"))),
                plainFirst.copy(collectionHints = plainFirst.collectionHints + "More"),
                plainFirst.copy(ordering = OrderingMode.Chronological),
            )
        changes.forEach { assertNotEquals(base, fingerprintOf(it), it.toString()) }
        // Length prefixes keep two texts from reading as one.
        assertNotEquals(
            fingerprintOf(plainFirst.copy(collectionHints = listOf("ab", "c"))),
            fingerprintOf(plainFirst.copy(collectionHints = listOf("a", "bc"))),
        )
        // A themed recipe's theme and filters are in it too.
        val themed = fingerprintOf(first)
        val theme = checkNotNull(first.theme)
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("Box Set 1"))
        assertNotEquals(themed, fingerprintOf(first.copy(theme = theme.copy(anchors = theme.anchors + anchor))))
        assertNotEquals(themed, fingerprintOf(first.copy(theme = theme.copy(animeOnly = !theme.animeOnly))))
        assertNotEquals(themed, fingerprintOf(first.copy(filters = moviesOnly)))
    }

    @Test
    fun everKidSafeKeysHoldEveryKidSafeKeyOfTheCatalogAndEveryManifest() {
        val current =
            StarterCatalog.recipes.filter { it.kidSafe }.map { it.key } +
                StarterCatalog.leftovers.filter { it.number in Band.Kids.numbers }.map { it.key }
        assertTrue(current.isNotEmpty())
        assertTrue(StarterCatalog.EVER_KID_SAFE_KEYS.containsAll(current), "the current catalog")
        for (manifest in CATALOG_MANIFESTS) {
            // From catalog 8 a ceiling no longer means kid-safe: Family and Teen channels have one too.
            val keptRecipes =
                manifest.recipes.filterValues { it.maxRating != null && (manifest.version < 8 || it.band == Band.Kids) }
            val kept =
                keptRecipes.keys +
                    manifest.leftovers.filterValues { it.maxRating != null && it.number in Band.Kids.numbers }.keys +
                    manifest.leftovers.filterValues { manifest.version < 8 && it.maxRating != null }.keys
            assertTrue(StarterCatalog.EVER_KID_SAFE_KEYS.containsAll(kept), "manifest ${manifest.version}")
        }
        assertTrue("kids-odds-and-ends" in StarterCatalog.EVER_KID_SAFE_KEYS)
    }

    @Test
    fun theEverKidSafeKeysAreStillTheVersionOneSet() {
        val v1 = CATALOG_MANIFEST_V1
        val expected =
            v1.recipes.filterValues { it.maxRating != null }.keys +
                v1.leftovers.filterValues { it.maxRating != null }.keys
        assertEquals(expected, StarterCatalog.EVER_KID_SAFE_KEYS)
    }

    @Test
    fun aChangeToWhatARecipeAirsNeedsANewerVersion() {
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("Box Set 1"))
        val changed =
            listOf(
                withFirst { it.copy(sources = it.sources + Source.Genre("Extra")) },
                withFirst { it.copy(theme = it.theme?.copy(anchors = it.theme.anchors + anchor)) },
                withFirst { it.copy(filters = moviesOnly) },
                withFirst { it.copy(maxRating = "G") },
                shipped + recipeOf("brand-new", 598),
            )
        for (recipes in changed) {
            assertTrue(problems(catalog(recipes)).isNotEmpty(), "caught at version 25")
            assertEquals(emptyList(), problems(catalog(recipes, version = 26)), "allowed at version 26")
        }
        val stricter = StarterCatalog.leftovers.map { if (it.number == 33) it.copy(maxRating = "G") else it }
        assertTrue(problems(catalog(leftovers = stricter)).isNotEmpty())
        assertEquals(emptyList(), problems(catalog(leftovers = stricter, version = 26)))
    }

    @Test
    fun aShippedKeyStaysAtItsNumberOrIsRetiredWithIt() {
        val moved = withFirst { it.copy(number = it.number + 1) }
        assertTrue(problems(catalog(moved, version = 26)).isNotEmpty(), "a shipped number never changes")

        val dropped = shipped.drop(1)
        assertTrue(problems(catalog(dropped, version = 26)).isNotEmpty(), "dropped without retiring")
        val keyOnly = catalog(dropped, version = 25, retiredKeys = setOf(first.key))
        assertTrue(problems(keyOnly).isNotEmpty(), "retired without its number")
        val retired = catalog(dropped, 26, retiredKeys = setOf(first.key), retiredNumbers = setOf(first.number))
        assertEquals(emptyList(), problems(retired))

        val noLeftovers = StarterCatalog.leftovers.filter { it.number != 199 }
        assertTrue(problems(catalog(leftovers = noLeftovers, version = 26)).isNotEmpty())
        val movedLeftovers = StarterCatalog.leftovers.map { if (it.number == 199) it.copy(number = 198) else it }
        assertTrue(problems(catalog(leftovers = movedLeftovers, version = 26)).isNotEmpty())
    }

    private companion object {
        val NEW_AT_8 =
            setOf(
                "big-screen-toons",
                "popcorn-matinee",
                "starship-sunday",
                "prime-time-pals",
                "homeroom",
                "mask-and-mantle",
                "level-up",
                "popcorn-epic",
                "nervous-laughter",
            )

        val PACK_AT_7 =
            setOf(
                "punchline",
                "game-night",
                "couch-talk",
                "matinee-tears",
                "sideline",
                "open-road",
                "simmer",
                "fixer-hour",
                "curtain-call",
                "order-in-the-court",
                "silver-screen",
                "dead-drop",
                "kung-fu-theater",
                "docs-and-dusters",
                "front-lines",
                "noir-nights",
                "life-stories",
                "disaster-zone",
                "starport",
                "drive-in-double",
                "neon-rain",
                "end-times",
                "whodunit-isles",
                "continental-drift",
                "seoul-sundays",
                "masala-hour",
                "telenovela-tonight",
                "one-season-only",
            )
    }
}
