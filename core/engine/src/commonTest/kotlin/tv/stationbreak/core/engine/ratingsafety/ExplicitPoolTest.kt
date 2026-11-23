// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.ratingsafety

import tv.stationbreak.core.engine.LIB
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.NO_NAMES
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.SERVER
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.AdultMark
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the SYNTHETIC adult slice ([SyntheticAdultSlice]) may air (#1078, the #1035 invariants): an Explicit title on
 * After Hours alone; an Adult title on no Family or Teen pool and in none of Odds & Ends 33, 66 and 99; a title with no
 * metadata out of Odds & Ends 199 but on a grown-up channel whose sources match it. Through the shipped recipes, their
 * policies (`KidStatus.policyOf`) and the leftovers pools, as the planner and the day build evaluate them.
 */
class ExplicitPoolTest {
    private val library = SyntheticAdultSlice.build()
    private val names = LibraryNames(library, NO_NAMES)
    private val explicit = SyntheticAdultSlice.EXPLICIT.idsOf()
    private val controls = SyntheticAdultSlice.CONTROLS.idsOf()
    private val noMetadata = SyntheticAdultSlice.NO_METADATA.entry.item.id
    private val afterHours = recipe(AdultMark.AFTER_HOURS_KEY)

    private fun List<SyntheticAdultSlice.Fixture>.idsOf(): Set<CanonicalId> = mapTo(HashSet()) { it.entry.item.id }

    private fun recipe(key: String): Recipe = checkNotNull(StarterCatalog.recipe(key)) { key }

    private fun rulesOf(recipe: Recipe) = RecipeResolver.resolve(recipe, SERVER, 0, NO_NAMES, names)

    private fun poolOf(recipe: Recipe): Set<CanonicalId> =
        PoolEvaluator
            .evaluate(rulesOf(recipe), library, KidStatus.policyOf(recipe))
            .items
            .mapTo(HashSet()) { it.id }

    private fun leftovers(
        ceiling: String?,
        policy: PoolPolicy,
    ): Set<CanonicalId> = Leftovers.pool(library, emptySet(), ceiling, policy).items.mapTo(HashSet()) { it.id }

    private fun broad(
        ceiling: String?,
        policy: PoolPolicy,
    ): Set<CanonicalId> =
        PoolEvaluator
            .evaluate(rules(listOf(Source.Library(LIB)), maxRating = ceiling), library, policy)
            .items
            .mapTo(HashSet()) { it.id }

    @Test
    fun theSliceLandsInTheTiersItPromises() {
        for (f in SyntheticAdultSlice.EXPLICIT) {
            assertEquals(RatingTier.Explicit, RatingTier.of(f.entry, f.series), f.title)
        }
        for (f in SyntheticAdultSlice.CONTROLS) {
            assertEquals(RatingTier.Adult, RatingTier.of(f.entry, f.series), f.title)
        }
        val bare = SyntheticAdultSlice.NO_METADATA
        assertEquals(RatingTier.Adult, RatingTier.of(bare.entry, bare.series))
    }

    @Test
    fun afterHoursIsExplicitOnlyAndItsPoolHoldsEveryExplicitTitleAndItsOwnSources() {
        assertEquals(PoolPolicy.ExplicitOnly, KidStatus.policyOf(afterHours))
        val pool = poolOf(afterHours)
        assertEquals(explicit, pool intersect explicit, "every Explicit title, matched by a source or not")
        // Its own sources still bring in grown-up titles: the TV-MA anime with the erotic tag is Adult and on it.
        val eroticTag = SyntheticAdultSlice.CONTROLS.single { it.title == "Synthetic TV-MA Erotic-Tag Anime" }
        assertTrue(eroticTag.entry.item.id in pool)
        assertTrue(noMetadata !in pool)
    }

    @Test
    fun noExplicitTitleIsInAnyOtherShippedPool() {
        for (recipe in StarterCatalog.recipes) {
            if (recipe.key == AdultMark.AFTER_HOURS_KEY) continue
            val leaked = poolOf(recipe) intersect explicit
            assertEquals(emptySet(), leaked, "${recipe.key} airs an Explicit title")
        }
        for (policy in listOf(PoolPolicy.GrownUp, PoolPolicy.Custom)) {
            for (ceiling in listOf(null, "PG", "TV-14")) {
                assertEquals(emptySet(), leftovers(ceiling, policy) intersect explicit, "leftovers $ceiling $policy")
            }
        }
        assertEquals(emptySet(), leftovers("PG", PoolPolicy.KidsOnly) intersect explicit, "leftovers 33")
        assertEquals(emptySet(), broad(null, PoolPolicy.GrownUp) intersect explicit, "a broad grown-up channel")
    }

    @Test
    fun noAdultTitleIsInAFamilyOrTeenPoolOrTheYoungLeftovers() {
        val adultOrUp =
            SyntheticAdultSlice.ALL
                .filter { RatingTier.of(it.entry, it.series) >= RatingTier.Adult }
                .mapTo(HashSet()) { it.entry.item.id }
        for (recipe in StarterCatalog.recipes.filter { it.band == Band.Family || it.band == Band.Teen }) {
            assertEquals(emptySet(), poolOf(recipe) intersect adultOrUp, "${recipe.key} airs an Adult title")
        }
        // The young leftovers, under their policy and under the fallback with no kids leftovers on.
        for (policy in listOf(PoolPolicy.GrownUp, PoolPolicy.Custom)) {
            for (ceiling in listOf("PG", "TV-14")) {
                assertEquals(emptySet(), leftovers(ceiling, policy) intersect adultOrUp, "leftovers $ceiling $policy")
            }
        }
        assertEquals(emptySet(), leftovers("PG", PoolPolicy.KidsOnly) intersect adultOrUp, "leftovers 33")
    }

    @Test
    fun youngBandsFollowTiersFamilyTakesFamilyAndTeenTakesFamilyAndTeen() {
        val family =
            SyntheticAdultSlice.YOUNG
                .single { it.title == "Synthetic Family Adventure" }
                .entry.item.id
        val teen =
            SyntheticAdultSlice.YOUNG
                .single { it.title == "Synthetic Teen Adventure" }
                .entry.item.id
        val familyBand = broad("PG", PoolPolicy.GrownUp)
        val teenBand = broad("TV-14", PoolPolicy.GrownUp)
        assertEquals(setOf(family), familyBand, "a Family-band pool takes the family tier only")
        assertTrue(family in teenBand && teen in teenBand, "a Teen-band pool takes family and teen")
        assertTrue(teenBand.all { it !in controls && it !in explicit && it != noMetadata })
        assertEquals(setOf(family), leftovers("PG", PoolPolicy.GrownUp), "Odds & Ends 66")
        assertTrue(teen in leftovers("TV-14", PoolPolicy.GrownUp), "Odds & Ends 99")
        // A custom channel under a young ceiling follows its band's tier limit (#1108); with no ceiling, or a higher
        // one, it has no hidden filters.
        val ecchi =
            SyntheticAdultSlice.CONTROLS
                .single { it.title == "Synthetic TV-14 Ecchi Anime" }
                .entry.item.id
        assertTrue(ecchi !in broad("TV-14", PoolPolicy.Custom), "a custom TV-14 channel follows the Teen band")
        assertEquals(teenBand, broad("TV-14", PoolPolicy.Custom), "and airs what that band's pool airs")
        assertEquals(familyBand, broad("PG", PoolPolicy.Custom), "a custom PG channel, the Family band's")
        assertTrue(ecchi in broad("R", PoolPolicy.Custom), "a custom channel above TV-14 keeps no hidden filters")
        assertTrue(explicit.any { it in broad(null, PoolPolicy.Custom) }, "nor drops Explicit titles")
    }

    @Test
    fun aTitleWithNoMetadataIsOutOfOddsAndEnds199ButOnAGrownUpChannelWhoseSourcesMatchIt() {
        for (policy in listOf(PoolPolicy.GrownUp, PoolPolicy.Custom)) {
            val pool = leftovers(null, policy)
            assertTrue(noMetadata !in pool, "Odds & Ends 199 under $policy")
            assertEquals(controls, pool intersect controls, "the controls stay in Odds & Ends 199 under $policy")
        }
        assertTrue(noMetadata in broad(null, PoolPolicy.GrownUp), "a library source matches it")
    }

    // #1035's deliberate mutations, each held here: break the rule and one of these fails.
    @Test
    fun theFourDeliberateMutationsAreEachCaught() {
        val hentai =
            SyntheticAdultSlice.EXPLICIT
                .single { it.title == "Synthetic Hentai Series" }
                .entry.item.id
        assertTrue(hentai !in poolOf(recipe("tokyo-after-school")), "an XXX hentai series on Tokyo After School")
        val sexploitation =
            SyntheticAdultSlice.EXPLICIT
                .first()
                .entry.item.id
        assertTrue(sexploitation !in poolOf(recipe("shag-carpet")), "an R sexploitation film on Shag Carpet")
        val ecchi =
            SyntheticAdultSlice.CONTROLS
                .single { it.title == "Synthetic TV-14 Ecchi Anime" }
                .entry.item.id
        assertTrue(ecchi !in poolOf(recipe("level-up")), "a TV-14 ecchi series on Level Up")
        assertTrue(noMetadata !in leftovers(null, PoolPolicy.GrownUp), "a no-metadata clip in Odds & Ends 199")
    }

    private fun curationOf(recipe: Recipe): CurationChannel {
        val rules = rulesOf(recipe)
        val pool = PoolEvaluator.evaluate(rules, library, KidStatus.policyOf(recipe)).items
        val settings = rules.toSchedulerSettings()
        return CurationChannel(ChannelId(recipe.key), recipe.number, recipe, false, rules, settings, pool)
    }

    private val explicitTitles: Set<CanonicalId> =
        SyntheticAdultSlice.EXPLICIT.mapTo(HashSet()) { it.entry.item.groupId }

    @Test
    fun withAfterHoursOnEveryExplicitTitleHomesThereAnimeIncluded() {
        val keys = listOf(AdultMark.AFTER_HOURS_KEY, "tokyo-after-school", "level-up", "shag-carpet", "neon-decade")
        val homes = Curation.homes(library, keys.map { curationOf(recipe(it)) })
        for (title in explicitTitles) assertEquals(ChannelId(AdultMark.AFTER_HOURS_KEY), homes.homeOf(title), "$title")
    }

    @Test
    fun withAfterHoursOffNoExplicitTitleAirsAndALateSwitchOnAirsThem() {
        val keys = listOf("tokyo-after-school", "level-up", "shag-carpet", "neon-decade")
        val others = keys.map { curationOf(recipe(it)) }
        val homes = Curation.homes(library, others)
        for (title in explicitTitles) assertNull(homes.homeOf(title), "$title has a home with After Hours off")
        assertEquals(emptySet(), leftovers(null, PoolPolicy.GrownUp) intersect explicit, "nor do the leftovers air it")
        val late = Curation.lateOn(library, curationOf(afterHours), homes)
        assertEquals(explicitTitles, late.homed intersect explicitTitles, "a late switch-on airs every Explicit title")
    }

    // An episode whose series row is missing can't show its series' explicit signals: fail up (fix round 1, MED-3).
    @Test
    fun anEpisodeOfAMissingSeriesLeavesGrownUpLeftoversAndGrownUpPoolsWithNoCeiling() {
        val missing = idOf(0xB00)
        val orphan = episode(idOf(0xB01), missing, rating = "TV-MA", genres = listOf("Anime"))
        val nr = episode(idOf(0xB02), missing, rating = "NR", tags = listOf("anime"))
        val snap = snapshot(items = listOf(orphan, nr))
        val ids = setOf(orphan.item.id, nr.item.id)
        for (policy in listOf(PoolPolicy.GrownUp, PoolPolicy.Custom)) {
            val pool = Leftovers.pool(snap, emptySet(), null, policy).items.map { it.id }
            assertEquals(emptyList(), pool, "Odds & Ends 199 under $policy")
        }
        val all = rules(listOf(Source.Library(LIB)))
        assertEquals(emptyList(), PoolEvaluator.evaluate(all, snap, PoolPolicy.GrownUp).items, "a grown-up pool")
        val tokyo = PoolEvaluator.evaluate(rulesOf(recipe("tokyo-after-school")), snap, PoolPolicy.GrownUp).items
        assertEquals(emptyList(), tokyo, "Tokyo After School")
        // A custom channel keeps no hidden filters, and After Hours' own sources still reach them.
        assertEquals(ids, PoolEvaluator.evaluate(all, snap, PoolPolicy.Custom).items.mapTo(HashSet()) { it.id })
    }

    // A stored After Hours below 100 (no edit puts it there) is held as grown-up, not Explicit-only (fix round 1).
    @Test
    fun afterHoursBelow100IsNotExplicitOnly() {
        val source = ChannelSource.Starter(AdultMark.AFTER_HOURS_KEY, false, rulesOf(afterHours))

        fun at(number: Int?): LineupChannel {
            val id = ChannelId("starter:after-hours")
            return LineupChannel(id, number, null, null, ChannelState.On, source, 0L)
        }
        assertEquals(PoolPolicy.ExplicitOnly, KidStatus.basePolicyOf(at(504)))
        assertEquals(PoolPolicy.ExplicitOnly, KidStatus.basePolicyOf(at(100)))
        assertEquals(PoolPolicy.ExplicitOnly, KidStatus.basePolicyOf(at(null)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(at(60)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(at(99)))
    }

    // Each Explicit tag, on an anime and on a non-anime film alike (#1088): in After Hours' pool, and in no other pool.
    @Test
    fun eachExplicitTagPutsAnimeAndNonAnimeFilmsOnAfterHoursAlone() {
        val films =
            RatingTier.EXPLICIT_TIER_TAGS.flatMapIndexed { i, tag ->
                listOf(
                    movie(idOf(0xC00 + 2 * i), rating = "TV-14", tags = listOf(tag)),
                    movie(idOf(0xC01 + 2 * i), rating = "TV-14", genres = listOf("Anime"), tags = listOf(tag)),
                )
            }
        val snap = snapshot(items = films)
        val ids = films.mapTo(HashSet()) { it.item.id }
        val ahPool = PoolEvaluator.evaluate(rulesOf(afterHours), snap, PoolPolicy.ExplicitOnly).items
        assertEquals(ids, ahPool.mapTo(HashSet()) { it.id }, "every tagged film airs on After Hours")
        for (recipe in StarterCatalog.recipes.filter { it.key != AdultMark.AFTER_HOURS_KEY }) {
            val pool = PoolEvaluator.evaluate(rulesOf(recipe), snap, KidStatus.policyOf(recipe)).items
            assertEquals(emptySet(), pool.mapTo(HashSet()) { it.id } intersect ids, recipe.key)
        }
        for (policy in listOf(PoolPolicy.GrownUp, PoolPolicy.Custom)) {
            assertEquals(emptyList(), Leftovers.pool(snap, emptySet(), null, policy).items, "leftovers $policy")
        }
    }
}
