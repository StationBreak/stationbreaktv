// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogV3Test {
    private val v3 = CATALOG_V3_RECIPES
    private val v2 = StarterCatalog.recipes
    private val animeOnlyKeys = setOf("tokyo-after-school", "giant-robot-hour", "power-level", "cherry-blossom-hour")

    private val anchoredKeys =
        setOf(
            "sing-along-station",
            "once-upon",
            "saturday-morning",
            "backyard-fort",
            "rainy-day-matinee",
            "little-spirits",
            "giggle-garage",
            "small-sleuths",
            "tall-tales-club",
            "spoof-central",
            "date-night",
            "open-road",
            "always-december",
            "curtain-call",
            "dead-drop",
            "big-rumble",
            "kung-fu-theater",
            "front-lines",
            "dusty-trails",
            "noir-nights",
            "starport",
            "rubber-suit-theatre",
            "midnight-projector",
            "the-cheap-seats",
            "cape-and-cowl",
            "jack-o-lantern-lounge",
            "paradox",
            "deep-space-drift",
            "creature-feature",
            "giant-robot-hour",
            "cartoons-after-dark",
            // Anchors that come only from a v2 collection hint.
            "critter-channel",
            "snowglobe",
            "spooky-sleepover",
            "tea-time",
        )

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
    fun everyNonAnimeRecipeOutsideKidsExcludesAnime() {
        for (r in v3.filter { it.band != Band.Kids && it.key !in animeOnlyKeys }) {
            assertTrue(Source.Genre("Anime") in r.exclusions, "${r.key} genre")
            assertTrue(Source.Tag("anime") in r.exclusions, "${r.key} tag")
        }
        for (key in animeOnlyKeys) {
            assertTrue(Source.Genre("Anime") !in recipe(key).exclusions, key)
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
    }

    @Test
    fun themeStudiosAreTheDecidedOnes() {
        val withStudios = v3.filter { checkNotNull(it.theme).themeStudios.isNotEmpty() }.map { it.key }
        assertEquals(listOf("kung-fu-theater", "rubber-suit-theatre", "tea-time", "cartoons-after-dark"), withStudios)
        assertEquals(
            listOf(NameMatcher.Prefix("Toho"), NameMatcher.Contains("Daiei"), NameMatcher.Contains("Tsuburaya")),
            recipe("rubber-suit-theatre").theme?.themeStudios,
        )
        assertEquals(
            listOf(
                NameMatcher.Contains("Adult Swim"),
                NameMatcher.Contains("Comedy Central"),
                NameMatcher.Exact("FX"),
                NameMatcher.Exact("FXX"),
                NameMatcher.Prefix("FX Productions"),
            ),
            recipe("cartoons-after-dark").theme?.themeStudios,
        )
        assertEquals(11, recipe("tea-time").theme?.themeStudios?.size)
        assertEquals(8, recipe("kung-fu-theater").theme?.themeStudios?.size)
    }

    @Test
    fun anchorsAreOrderedCollectionsThenTitlesThenStudiosUnlessTheTableSaysOtherwise() {
        val anchored = v3.filter { checkNotNull(it.theme).anchors.isNotEmpty() }.map { it.key }.toSet()
        assertEquals(anchoredKeys, anchored)
        assertEquals(
            AnchorKind.Collection,
            recipe("sing-along-station")
                .theme
                ?.anchors
                ?.first()
                ?.kind,
        )
        assertEquals(
            AnchorKind.Title,
            recipe("deep-space-drift")
                .theme
                ?.anchors
                ?.first()
                ?.kind,
        )
        assertEquals(
            AnchorKind.Studio,
            recipe("cartoons-after-dark")
                .theme
                ?.anchors
                ?.first()
                ?.kind,
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
    fun guestOnlyIsExactlyTheYearRangeRecipes() {
        val yearKeys =
            v3.filter { r -> r.sources.any { it is Source.YearRange } }.map { it.key }.toSet()
        assertEquals(yearKeys, v3.filter { it.guestOnly }.map { it.key }.toSet())
        assertEquals(
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
            ),
            yearKeys,
        )
    }

    @Test
    fun noRecipeHasCollectionHints() {
        assertTrue(v3.all { it.collectionHints.isEmpty() })
    }

    @Test
    fun keysNumbersBandsCeilingsAndOptInMatchTheLiveCatalog() {
        assertEquals(v2.map { it.key }, v3.map { it.key })
        for ((old, new) in v2.zip(v3)) {
            assertEquals(old.number, new.number, old.key)
            assertEquals(old.band, new.band, old.key)
            assertEquals(old.maxRating, new.maxRating, old.key)
            assertEquals(old.optIn, new.optIn, old.key)
        }
    }

    @Test
    fun everyKidRecipeStaysKidSafe() {
        for (new in v3.filter { it.kidSafe }) {
            val old = checkNotNull(StarterCatalog.recipe(new.key))
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
            // It still resolves: anchors are rules sources, which the kid hold filters like any other.
            RecipeResolver.resolve(new, SERVER, 0, NO_NAMES, NO_LIBRARY)
        }
    }

    @Test
    fun theLiveCatalogIsStillVersionTwo() {
        assertEquals(2, StarterCatalog.VERSION)
        assertNull(StarterCatalog.recipes.firstOrNull { it.theme != null })
    }
}
