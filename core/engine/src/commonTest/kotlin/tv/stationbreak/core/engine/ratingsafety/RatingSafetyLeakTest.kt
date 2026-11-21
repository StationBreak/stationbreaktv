// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.ratingsafety

import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.LIB
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The rating-safety leak check of #576 and #992, on the never-in-kids fixtures ([NeverInKids]) and the synthetic
 * library ([SyntheticLibrary]). A leak toward kids is always a failure.
 */
class RatingSafetyLeakTest {
    private val library = SyntheticLibrary.build()
    private val all = rules(listOf(Source.Library(LIB)))

    private fun seriesOf(entry: RuleItem): RuleSeries? = entry.item.seriesId?.let { library.series[it] }

    private fun tierOf(entry: RuleItem): RatingTier = RatingTier.of(entry, seriesOf(entry))

    private fun names(
        entry: RuleItem,
        pick: (RuleItem) -> List<String>,
        pickSeries: (RuleSeries) -> List<String>,
    ): List<String> = (pick(entry) + seriesOf(entry)?.let(pickSeries).orEmpty()).map { it.lowercase() }

    @Test
    fun noNeverInKidsFixtureNorItsStrippedCopyReachesKidsOrFamily() {
        for (fixture in NeverInKids.ALL) {
            val tier = RatingTier.of(fixture.entry, fixture.series)
            assertTrue(tier == RatingTier.Teen || tier == RatingTier.Adult, "${fixture.title}: $tier")
            assertTrue(!KidHold.admits(fixture.entry, fixture.series), fixture.title)
        }
        // The stripped copies share the seed's IDs, so each set is its own snapshot.
        for (set in listOf(NeverInKids.SEED, NeverInKids.SEED.map { it.stripped() })) {
            val snap = snapshot(items = set.map { it.entry }, series = set.mapNotNull { it.series })
            for (ceiling in listOf(null) + RatingLadder.names) {
                val kids = PoolEvaluator.evaluate(all.copy(maxRating = ceiling), snap, PoolPolicy.KidsOnly)
                assertEquals(emptyList(), kids.items, "kids pool under $ceiling")
            }
        }
    }

    @Test
    fun theSeedListLandsAdultAndSleepersIsNeverKids() {
        for (fixture in NeverInKids.SEED) {
            assertEquals(RatingTier.Adult, RatingTier.of(fixture.entry, fixture.series), fixture.title)
        }
        val sleepers = NeverInKids.SEED.single { it.title == "Sleepers" }
        assertTrue("kids" in sleepers.entry.tags, "the fixture carries the TMDb kids keyword")
        assertNotEquals(RatingTier.Kids, RatingTier.of(sleepers.entry, null))
        // Without its rating the keyword still does not admit it. (A kids keyword next to a kid rating is evidence,
        // which is how a G-rated Totoro reaches kids: a grown-up film mis-rated G with that keyword would too.)
        assertNotEquals(RatingTier.Kids, RatingTier.of(sleepers.entry.copy(officialRating = null), null))
    }

    @Test
    fun noExplicitOrGoreTaggedTitleReachesKidsOrFamily() {
        val flagged = (RatingTier.EXPLICIT_TAGS + RatingTier.GORE_TAGS).toSet()
        var checked = 0
        for (entry in library.items) {
            if (names(entry, { it.tags }, { it.tags }).none { it in flagged }) continue
            checked++
            val tier = tierOf(entry)
            assertTrue(tier == RatingTier.Teen || tier == RatingTier.Adult, "$entry: $tier")
        }
        assertTrue(checked > 1_000, "flagged titles checked: $checked")
    }

    @Test
    fun anUnratedOrUnknownTitleIsAdultAndOnNoKidChannelUnderAnyCeiling() {
        val unrated = library.items.filter { RatingTier.decisiveLevel(it, seriesOf(it)) == null }
        assertTrue(unrated.size > 5_000, "unrated titles: ${unrated.size}")
        for (entry in unrated) assertEquals(RatingTier.Adult, tierOf(entry), "$entry")
        val ids = unrated.mapTo(HashSet()) { it.item.id }
        for (ceiling in listOf(null) + RatingLadder.names) {
            val kids = PoolEvaluator.evaluate(all.copy(maxRating = ceiling), library, PoolPolicy.KidsOnly)
            assertTrue(kids.items.none { it.id in ids }, "an unrated title on a kid channel under $ceiling")
        }
    }

    // A G movie with no kids genre, kids tag, kid studio or Animation and Family, and no veto.
    private fun isPlainG(entry: RuleItem): Boolean {
        val genres = entry.genres.map { it.lowercase() }
        val tags = entry.tags.map { it.lowercase() }
        val evidence =
            "kids" in genres || "kids" in tags || "children" in tags || "Nickelodeon" in entry.studios ||
                ("animation" in genres && "family" in genres)
        val vetoed = "adult" in genres || tags.any { it in RatingTier.EXPLICIT_TAGS || it in RatingTier.GORE_TAGS }
        return entry.officialRating == "G" && entry.item.seriesId == null && !evidence && !vetoed
    }

    @Test
    fun aGRatedTitleWithoutKidEvidenceIsFamily() {
        val plain = library.items.filter(::isPlainG)
        assertTrue(plain.size >= 20, "G titles without evidence: ${plain.size}")
        for (entry in plain) assertEquals(RatingTier.Family, tierOf(entry), "$entry")
    }

    @Test
    fun everyTitleIsInExactlyOneTierAndTheHoldsAgree() {
        val kidsPool = PoolEvaluator.evaluate(all, library, PoolPolicy.KidsOnly).items.mapTo(HashSet()) { it.id }
        val grownUp = PoolEvaluator.evaluate(all, library, PoolPolicy.GrownUp).items.mapTo(HashSet()) { it.id }
        for (entry in library.items) {
            val kids = tierOf(entry) == RatingTier.Kids
            assertEquals(kids, entry.item.id in kidsPool, "kid channel: $entry")
            assertEquals(!kids, entry.item.id in grownUp, "grown-up channel: $entry")
            assertEquals(kids, GrownUpHold.dropsFromGrownUp(entry, seriesOf(entry)), "$entry")
        }
    }

    // Fail upward: an explicit tag sends anything to adult, a gore tag never moves a title down, and losing the rating
    // makes it adult.
    @Test
    fun aRejectionOnlyEverMovesATitleUp() {
        for (entry in library.items) {
            val series = seriesOf(entry)
            val tier = RatingTier.of(entry, series)
            assertEquals(RatingTier.Adult, RatingTier.of(entry.copy(tags = entry.tags + "nudity"), series), "$entry")
            assertTrue(RatingTier.of(entry.copy(tags = entry.tags + "gore"), series) >= tier, "$entry")
            if (entry.item.seriesId == null) {
                assertEquals(RatingTier.Adult, RatingTier.of(entry.copy(officialRating = null), null), "$entry")
            }
        }
    }

    // The tier counts of this synthetic library, pinned so a change to the rules shows up here. Not a target: a
    // deliberate rule change updates them, with the reason in the commit.
    @Test
    fun theSyntheticLibraryTierCounts() {
        val counts = library.items.groupingBy { tierOf(it) }.eachCount()
        assertEquals(EXPECTED_COUNTS, RatingTier.entries.associateWith { counts[it] ?: 0 })
    }

    private companion object {
        val EXPECTED_COUNTS =
            mapOf(RatingTier.Kids to 989, RatingTier.Family to 1059, RatingTier.Teen to 2380, RatingTier.Adult to 20313)
    }
}
