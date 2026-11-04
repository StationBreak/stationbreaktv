// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogV3Test {
    private val v3 = CATALOG_V3_RECIPES

    // Catalog 2's recipes, which catalog 3 replaced.
    private val v2 = CATALOG_V2_RECIPES
    private val animeOnlyKeys = setOf("tokyo-after-school", "giant-robot-hour", "power-level", "cherry-blossom-hour")

    private fun recipe(key: String): Recipe = checkNotNull(v3.firstOrNull { it.key == key }) { "no recipe $key" }

    private fun genreNames(r: Recipe): List<String> =
        r.sources.filterIsInstance<Source.Genre>().map { it.name } + r.filters?.genres.orEmpty()

    @Test
    fun everyGenreIsATmdbGenreOrAnime() {
        for (r in v3) {
            for (name in genreNames(r)) {
                assertTrue(name in TMDB_GENRES || name == "Anime", "${r.key} has genre $name")
            }
        }
    }

    @Test
    fun everyRecipeIsThemedAndCurated() {
        for (r in v3) {
            assertNotNull(r.theme, r.key)
            assertEquals(OrderingMode.Curated, r.ordering, r.key)
            assertTrue(r.collectionHints.isEmpty(), r.key)
        }
    }

    @Test
    fun theFourAnimeChannelsAreAnimeOnly() {
        assertEquals(animeOnlyKeys, v3.filter { checkNotNull(it.theme).animeOnly }.map { it.key }.toSet())
    }

    @Test
    fun onlyRainyDayMatineeHasFamilyAmongKidRecipes() {
        val withFamily = v3.filter { it.kidSafe && Source.Genre("Family") in it.sources }
        assertEquals(listOf("rainy-day-matinee"), withFamily.map { it.key })
    }

    @Test
    fun laughTrackIsComedyAloneAndComedyCountyIsGenreComedy() {
        val laugh = recipe("laugh-track")
        val sitcom = listOf(Source.Genre("Comedy"), Source.Tag("sitcom"), Source.Tag("situation comedy"))
        assertEquals(sitcom, laugh.sources)
        assertEquals(TMDB_GENRES - setOf("Comedy", "Family", "Romance"), laugh.filters?.notGenres?.toSet())
        val county = recipe("satire-desk")
        assertEquals("Comedy County", county.name)
        assertEquals("Comedy with a twist of something else.", county.personality)
        assertEquals(listOf(Source.Genre("Comedy")), county.sources)
        assertEquals(
            listOf(
                "Crime",
                "Action",
                "Mystery",
                "Fantasy",
                "Science Fiction",
                "Music",
                "Action & Adventure",
                "Sci-Fi & Fantasy",
            ),
            county.filters?.genres,
        )
    }

    @Test
    fun comedyCountyKeepsSatireDesksKeyAndNumber() {
        val county = recipe("satire-desk")
        assertEquals(528, county.number)
        assertEquals(Band.Premium, county.band)
    }

    @Test
    fun theFormatPromisesAreWhereTheyShouldBe() {
        val matinee = recipe("rainy-day-matinee")
        assertEquals(FormatPromise.Films, matinee.theme?.format)
        assertEquals(FilterKind.Movie, matinee.filters?.kind)
        for (key in listOf("laugh-track", "satire-desk")) {
            val r = recipe(key)
            assertEquals(FormatPromise.ShortSeries, r.theme?.format, key)
            assertEquals(FilterKind.Episode, r.filters?.kind, key)
            assertEquals(35, r.filters?.maxRuntimeMinutes, key)
            assertTrue("Animation" in r.filters?.notGenres.orEmpty(), key)
        }
        val promised = v3.filter { it.theme?.format != null }.map { it.key }.toSet()
        assertEquals(setOf("rainy-day-matinee", "laugh-track", "satire-desk"), promised)
    }

    @Test
    fun mustAlsoMatchFiltersAreWhereTheyShouldBe() {
        val kungFu = recipe("kung-fu-theater").filters
        assertEquals(listOf("Action", "Adventure", "Drama", "Fantasy", "History"), kungFu?.genres)
        assertEquals(listOf("Science Fiction", "Sci-Fi"), kungFu?.notGenres)
        assertEquals(listOf("Science Fiction", "Sci-Fi & Fantasy"), recipe("deep-space-drift").filters?.genres)
        assertEquals(listOf("Horror", "Science Fiction", "Thriller"), recipe("creature-feature").filters?.genres)
        assertEquals(listOf("Animation"), recipe("cartoons-after-dark").filters?.genres)
        assertEquals(
            listOf(
                "Science Fiction",
                "Horror",
                "Action",
                "Adventure",
                "Fantasy",
                "Thriller",
                "Sci-Fi & Fantasy",
                "Action & Adventure",
            ),
            recipe("rubber-suit-theatre").filters?.genres,
        )
    }

    @Test
    fun aV2CollectionHintBecomesAnExactCollectionAnchorUnlessAnAnchorCoversIt() {
        val dino = recipe("critter-channel").theme?.anchors
        assertEquals(listOf(Anchor(AnchorKind.Collection, NameMatcher.Exact("Dinosaurs"))), dino)
        // Covered by a prototype anchor, so not added twice.
        val westerns = recipe("dusty-trails").theme?.anchors.orEmpty()
        assertEquals(1, westerns.size)
    }

    @Test
    fun noRecipeHasCollectionHints() {
        assertTrue(v3.all { it.collectionHints.isEmpty() })
    }

    // What catalog 2 promised, as frozen in its manifest, and in catalog order.
    @Test
    fun keysNumbersBandsCeilingsAndOptInMatchCatalogTwo() {
        assertEquals(v2.map { it.key }, v3.map { it.key })
        assertEquals(CATALOG_MANIFEST_V2.recipes.keys, v3.map { it.key }.toSet())
        for (new in v3) {
            val old = CATALOG_MANIFEST_V2.recipes.getValue(new.key)
            assertEquals(old.number, new.number, new.key)
            assertEquals(old.band, new.band, new.key)
            assertEquals(old.maxRating, new.maxRating, new.key)
            assertEquals(old.optIn, new.optIn, new.key)
        }
    }

    @Test
    fun grownUpRecipesKeepTheKidsGenreExclusionsAndAnimeAndKidRecipesHaveNone() {
        val kidsGenres = KidHold.KIDS_GENRES.map { Source.Genre(it) }
        for (r in v3) {
            if (r.band == Band.Kids) {
                assertEquals(emptyList(), r.exclusions, r.key)
                continue
            }
            assertTrue(r.exclusions.containsAll(kidsGenres), "${r.key} lets a kids' genre in")
            val excludesAnime = Source.Genre("Anime") in r.exclusions && Source.Tag("anime") in r.exclusions
            assertEquals(r.key !in animeOnlyKeys, excludesAnime, r.key)
        }
    }

    @Test
    fun guestOnlyIsExactlyTheYearRangeRecipes() {
        val expected =
            setOf(
                "flipbook",
                "rabbit-ears",
                "shag-carpet",
                "neon-decade",
                "dial-up",
                "flip-phone",
                "binge-row",
                "fresh-cut",
                "silver-screen",
            )
        assertEquals(expected, v3.filter { it.guestOnly }.map { it.key }.toSet())
    }

    @Test
    fun everyKidRecipeStaysKidSafe() {
        for (new in v3.filter { it.kidSafe }) {
            val old = v2.single { it.key == new.key }
            assertEquals(old.maxRating, new.maxRating, new.key)
            assertTrue(
                new.theme
                    ?.themeStudios
                    .orEmpty()
                    .isEmpty(),
                "${new.key} has theme studios",
            )
            // No kid recipe gains a genre source its live recipe did not have.
            val oldGenres = old.sources.filterIsInstance<Source.Genre>()
            for (g in new.sources.filterIsInstance<Source.Genre>()) {
                assertTrue(g in oldGenres, "${new.key} gains genre ${g.name}")
            }
            assertAnchorsPassTheKidHold(new)
        }
    }

    // A neutral library: for every collection or title anchor, a matching collection or title that holds three
    // films. Only the anchors can reach them. The R film is above every kid ceiling, so it proves the ceiling still
    // applies. The G drama is inside the ceiling but not made for kids, so it proves the made-for-kids gate still
    // applies. The TV-Y film passes both, and being kept proves the anchor reaches the pool.
    private fun assertAnchorsPassTheKidHold(r: Recipe) {
        val anchors = checkNotNull(r.theme).anchors.filter { it.kind != AnchorKind.Studio }
        if (anchors.isEmpty()) return
        val items = ArrayList<RuleItem>()
        val collections = ArrayList<RuleCollection>()
        val collectionNames = LinkedHashMap<CanonicalId, String>()
        val grownUp = HashSet<CanonicalId>()
        val kids = HashSet<CanonicalId>()
        for ((i, anchor) in anchors.withIndex()) {
            val name = anchor.match.folded
            val adult = movie(idOf(0x100 + 3 * i), rating = "R", genres = listOf("Drama"))
            val plain = movie(idOf(0x102 + 3 * i), rating = "G", genres = listOf("Drama"))
            val kid = movie(idOf(0x101 + 3 * i), rating = "TV-Y")
            grownUp.add(adult.item.id)
            grownUp.add(plain.item.id)
            kids.add(kid.item.id)
            if (anchor.kind == AnchorKind.Collection) {
                val id = idOf(0x1000 + i)
                collections.add(collection(id, movies = setOf(adult.item.id, kid.item.id, plain.item.id)))
                collectionNames[id] = name
                items.add(adult)
                items.add(kid)
                items.add(plain)
            } else {
                items.add(adult.copy(name = name))
                items.add(kid.copy(name = name))
                items.add(plain.copy(name = name))
            }
        }
        val names = CollectionNames(collectionNames)
        val library = snapshot(items = items, collections = collections)
        val rules = RecipeResolver.resolve(r, SERVER, 0, names, LibraryNames(library, names))
        val pool = PoolEvaluator.evaluate(rules, library, KidStatus.policyOf(r))
        val kept = pool.items.map { it.id }.toSet()
        assertEquals(kids, kept, "${r.key}: the kid films are kept and the grown-up ones held out")
        assertTrue(kept.none { it in grownUp }, r.key)
    }

    private fun kindLetter(kind: AnchorKind): Char =
        when (kind) {
            AnchorKind.Collection -> 'C'
            AnchorKind.Title -> 'T'
            AnchorKind.Studio -> 'S'
        }

    private fun matcherLetter(match: NameMatcher): Char =
        when (match) {
            is NameMatcher.Exact -> 'E'
            is NameMatcher.Prefix -> 'P'
            is NameMatcher.Contains -> 'C'
            is NameMatcher.Word -> 'W'
        }

    private fun digestOf(matchers: List<NameMatcher>): String =
        fnv1a64(matchers.joinToString("\n") { it.folded }.encodeToByteArray()).toString(HEX).padStart(HEX, '0')

    // Count, then a kind letter and a matcher letter for each anchor in order, then a digest of the folded names.
    private fun anchorPin(r: Recipe): String {
        val anchors = checkNotNull(r.theme).anchors
        val shape = anchors.joinToString(" ") { "${kindLetter(it.kind)}${matcherLetter(it.match)}" }
        return "${anchors.size} $shape ${digestOf(anchors.map { it.match })}"
    }

    private fun studioPin(r: Recipe): String {
        val studios = checkNotNull(r.theme).themeStudios
        return "${studios.size} ${studios.map(::matcherLetter).joinToString(" ")} ${digestOf(studios)}"
    }

    @Test
    fun theAnchorPortIsPinnedPerRecipe() {
        val actual = v3.filter { checkNotNull(it.theme).anchors.isNotEmpty() }.associate { it.key to anchorPin(it) }
        assertEquals(ANCHOR_PINS, actual)
    }

    @Test
    fun theThemeStudiosArePinnedPerRecipe() {
        val themed = v3.filter { checkNotNull(it.theme).themeStudios.isNotEmpty() }
        val actual = themed.associate { it.key to studioPin(it) }
        assertEquals(STUDIO_PINS, actual)
    }

    private companion object {
        const val HEX = 16
        val ANCHOR_PINS: Map<String, String> =
            mapOf(
                "sing-along-station" to "2 CE CE 5712baf4006b764f",
                "once-upon" to "7 CC CC CC CC CC CC CC ce8080ee78ee0447",
                "saturday-morning" to "7 TC TC TC TC TC TC SC fe91ece31251f408",
                "backyard-fort" to "5 CE CC CC CC CC 7cd2fff98c7f6103",
                "critter-channel" to "1 CE 152202ed9091902b",
                "rainy-day-matinee" to "6 CE CC CC CC CC CC fa7c3dbe02ed0065",
                "little-spirits" to "2 CC CC 7c01b9491d7d3f37",
                "snowglobe" to "1 CE 706901514473f67b",
                "giggle-garage" to "5 CC CC TC TC TC b74581ee91f458c0",
                "small-sleuths" to "2 CC TC 2d3ecea5830e4066",
                "tall-tales-club" to "5 CC CC CC CC CC cd1c5c39e9fa9a9d",
                "spooky-sleepover" to "1 CE 7b1838992abd5610",
                "spoof-central" to "5 CC CC CC CC CC f325453ba896fe0a",
                "date-night" to "2 CC CC 8f09b42cbbc4db14",
                "open-road" to "1 CC 09b461b2dbd25c67",
                "always-december" to "5 CE CC CC CC CC 605e02b71d9c239c",
                "curtain-call" to "1 CE a41c6eb35250d6de",
                "silver-screen" to "6 CC CC CC CC CC CC b025a07232a56b96",
                "dead-drop" to "5 CC CC CC CC TE a114d47061f79bd7",
                "big-rumble" to "9 CC CC CC CC CC CC CC CP CC f7fdf2e3b2ad23d0",
                "kung-fu-theater" to "2 CC CC 6c0aaecbbc916f29",
                "front-lines" to "2 TP CC 29280815f17c088f",
                "dusty-trails" to "1 CE 97cedc9dfae2eaf2",
                "noir-nights" to "2 CC CC d631cf2e8ca00baf",
                "starport" to "5 CC CC CC CP TP 5e4078882166f323",
                "rubber-suit-theatre" to "4 CC CC CC CE 8ed8ce1f6c89cdca",
                "midnight-projector" to "4 CC CC CC CC 6602327b8627efac",
                "the-cheap-seats" to "4 CE TC TP SC fb3069c5ecdba8ad",
                "cape-and-cowl" to "7 CC CC CC CC CC CC TC 2bec43d69e5fcdb7",
                "jack-o-lantern-lounge" to "6 CC CE CC CC CC CC 8e7e7ba4b5043d38",
                "paradox" to "1 CC 2724622aac041bd0",
                "deep-space-drift" to "5 TC TC TC TC CC a677c7cd1de3d7f8",
                "creature-feature" to "3 CC CC CC f86cab0b20215ddd",
                "giant-robot-hour" to "4 TC TC TC TC 7011d495573fbd6b",
                "tea-time" to "1 CE 939e2a7ac2fed760",
                "cartoons-after-dark" to "4 SC SC CC CC 9c2b04cc7e35d92f",
            )
        val STUDIO_PINS: Map<String, String> =
            mapOf(
                "kung-fu-theater" to "8 C C C C C C C C 1dab6d9025dc0aa9",
                "rubber-suit-theatre" to "3 W C C f6f880740abb9213",
                "tea-time" to "11 P W C P W P C C C C C db89332bceb39108",
                "cartoons-after-dark" to "5 C C E E P 56928bf9033f4b0f",
            )
    }
}
