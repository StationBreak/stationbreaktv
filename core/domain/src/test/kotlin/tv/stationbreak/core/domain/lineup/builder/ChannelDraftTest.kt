// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a draft says about itself: its problems, the settings it shows, and the family cap. */
class ChannelDraftTest {
    private val comedy = DraftSource.Genre("Comedy", "Comedy")

    @Suppress("LongParameterList") // A test builder: every field has a default.
    private fun draft(
        name: String = "Mine",
        sources: List<DraftSource> = listOf(comedy),
        order: PlayOrder? = PlayOrder.Shuffle,
        tier: FamilyTier? = null,
        filters: DraftFilters = DraftFilters(),
        kept: KeptSettings? = null,
    ) = ChannelDraft(name, sources, emptyList(), filters, order, 3, 1, tier, 600, kept, false, null, Seed(1uL))

    @Test
    fun aDraftThatCanBeSavedHasNoProblems() {
        assertEquals(emptyList(), draft().problems())
        assertNull(draft().problem())
    }

    @Test
    fun problemsListsEveryOneInStepOrder() {
        val bad =
            draft(
                name = " ",
                sources = emptyList(),
                filters = DraftFilters(yearFrom = 1990, minMinutes = 90, maxMinutes = 30),
            ).copy(seriesCap = 11)

        assertEquals(
            listOf(
                DraftProblem.NoSources,
                DraftProblem.YearsIncomplete,
                DraftProblem.MinutesReversed,
                DraftProblem.CapOutOfRange,
                DraftProblem.NameEmpty,
            ),
            bad.problems(),
        )
        assertTrue(checkNotNull(bad.problem()) in bad.problems())
    }

    @Test
    fun problemsAreEmptyExactlyWhenProblemIsNull() {
        val drafts =
            listOf(
                draft(),
                draft(name = ""),
                draft(sources = emptyList()),
                draft(filters = DraftFilters(yearFrom = 2000, yearTo = 1990)),
                draft().copy(number = 5, numberChosen = true),
            )
        drafts.forEach { assertEquals(it.problem() == null, it.problems().isEmpty(), "$it") }
    }

    @Test
    fun capsAreShownForShuffleAndRotationButNotForInOrderOrAKeptOrder() {
        assertTrue(draft(order = PlayOrder.Shuffle).showsCaps())
        assertTrue(draft(order = PlayOrder.Rotation).showsCaps())
        assertFalse(draft(order = PlayOrder.InOrder).showsCaps())
        val kept = KeptSettings("Western", listOf(KeptItem.Order(KeptOrder.Chronological)))
        assertFalse(draft(order = null, kept = kept).showsCaps())
        assertTrue(draft(order = PlayOrder.Shuffle, kept = kept).showsCaps())
    }

    // #560: the rows must not say 3 in a row beside a kept line that says there is no limit.
    @Test
    fun theCapsShownAreNoLimitWhileAKeptUncappedShuffleIsInForce() {
        val uncapped = draft(order = null, kept = KeptSettings("Western", listOf(KeptItem.NoCaps)))
        val kept = uncapped.copy(seriesCap = null, filmCap = null)
        assertNull(kept.shownSeriesCap())
        assertNull(kept.shownFilmCap())
        // Once the viewer gives caps a value they take the kept item's place.
        val chosen = kept.copy(seriesCap = 4, filmCap = 2)
        assertEquals(4, chosen.shownSeriesCap())
        assertEquals(2, chosen.shownFilmCap())
        assertEquals(3, draft().shownSeriesCap())
        assertEquals(1, draft().shownFilmCap())
    }

    @Test
    fun onlyAShuffleCanBeReshuffled() {
        assertTrue(draft(order = PlayOrder.Shuffle).canReshuffle())
        assertFalse(draft(order = PlayOrder.Rotation).canReshuffle())
        assertFalse(draft(order = PlayOrder.InOrder).canReshuffle())
        val kept = KeptSettings("Western", listOf(KeptItem.Order(KeptOrder.RandomInOrder)))
        assertFalse(draft(order = null, kept = kept).canReshuffle())
        val uncapped = KeptSettings("Western", listOf(KeptItem.NoCaps))
        assertTrue(draft(order = null, kept = uncapped).canReshuffle())
    }

    @Test
    fun aDraftHasAPoolWithASourceOrAKeptYearRange() {
        assertTrue(draft().hasPool())
        assertFalse(draft(sources = emptyList()).hasPool())
        val years = KeptSettings("Eighties", listOf(KeptItem.YearsSource(1980, 1989)))
        assertTrue(draft(sources = emptyList(), kept = years).hasPool())
    }

    @Test
    fun onlyKeptSettingsTheBuilderHasControlsForCanBeReplaced() {
        fun kept(vararg items: KeptItem) = KeptSettings("Western", items.toList())

        assertFalse(draft().keepsReplaceable())
        assertTrue(draft(order = null, kept = kept(KeptItem.Order(KeptOrder.Chronological))).keepsReplaceable())
        val uncapped = draft(order = null, kept = kept(KeptItem.NoCaps)).copy(seriesCap = null, filmCap = null)
        assertTrue(uncapped.keepsReplaceable())
        // The short day matters only where no caps are written (In order here); caps would need 24 hours.
        assertTrue(draft(order = PlayOrder.InOrder, kept = kept(KeptItem.EpochHours(12))).keepsReplaceable())
        assertFalse(draft(kept = kept(KeptItem.EpochHours(12))).keepsReplaceable())
        // No control replaces these: they are information only.
        val fixed =
            kept(
                KeptItem.BlockLength(4),
                KeptItem.BackToBackOff,
                KeptItem.Filler,
                KeptItem.OtherSettings,
                KeptItem.YearsSource(1980, 1989),
            )
        assertFalse(draft(kept = fixed).keepsReplaceable())
        assertEquals(5, draft(kept = fixed).keptInForce.size)
        // Once the viewer has chosen an order, the kept order is no longer in force, so nothing is left to replace.
        assertFalse(draft(kept = kept(KeptItem.Order(KeptOrder.Chronological))).keepsReplaceable())
    }

    @Test
    fun theCapRangeIsOneToTen() {
        assertEquals(1..10, ChannelDraft.CAP_RANGE)
    }

    @Test
    fun turningKidsOnTakesTheNumberAndHoldsALooserCeilingAtWhatTheHoldAllows() {
        val grownUp = draft(filters = DraftFilters(maxRating = "R"))

        val kids = grownUp.withTier(FamilyTier.Tots, number = 5)

        assertEquals(FamilyTier.Tots, kids.tier)
        assertEquals(5, kids.number)
        assertFalse(kids.numberChosen)
        assertEquals("PG", kids.filters.maxRating)
    }

    @Test
    fun turningKidsOnWithNoCeilingIsPgAndAKidsChoiceStays() {
        assertEquals("PG", draft().withTier(FamilyTier.Tots, number = 5).filters.maxRating)
        val g = draft(filters = DraftFilters(maxRating = "G"))
        assertEquals("G", g.withTier(FamilyTier.Tots, number = 5).filters.maxRating)
        assertEquals(
            "TV-Y7",
            draft(filters = DraftFilters(maxRating = "TV-Y7")).withTier(FamilyTier.Tots, number = 5).filters.maxRating,
        )
    }

    @Test
    fun turningKidsOffLeavesTheCeilingAlone() {
        val kids = draft(tier = FamilyTier.Tots, filters = DraftFilters(maxRating = "G"))

        val off = kids.withTier(null, number = 610)

        assertNull(off.tier)
        assertEquals(610, off.number)
        assertEquals("G", off.filters.maxRating)
    }

    @Test
    fun turningKidsOnHoldsALooserCeilingAtPgAndTeensAtTv14() {
        val loose = draft(filters = DraftFilters(maxRating = "R"))

        assertEquals("PG", loose.withTier(FamilyTier.Kids, 40).filters.maxRating)
        assertEquals("TV-14", loose.withTier(FamilyTier.Teens, 70).filters.maxRating)
        assertEquals("TV-14", draft().withTier(FamilyTier.Teens, 70).filters.maxRating, "no ceiling is the top")
        assertEquals(FamilyTier.Teens, loose.withTier(FamilyTier.Teens, 70).tier)
    }

    @Test
    fun aTeensCeilingAtOrBelowTv14StaysAsItIs() {
        for (rating in listOf("PG", "TV-PG", "PG-13", "TV-14", "G", "TV-Y7-FV")) {
            val kept = draft(filters = DraftFilters(maxRating = rating)).withTier(FamilyTier.Teens, 70)

            assertEquals(rating, kept.filters.maxRating)
        }
    }

    @Test
    fun aKidsTierCeilingIsAKidsHoldChoice() {
        fun held(rating: String) = draft(filters = DraftFilters(maxRating = rating)).withTier(FamilyTier.Kids, 40)

        assertEquals("G", held("G").filters.maxRating)
        assertEquals("PG", held("TV-14").filters.maxRating)
    }

    @Test
    fun aPickedNumberMustBeInTheBandOfItsTier() {
        fun problem(
            tier: FamilyTier?,
            number: Int,
        ) = draft(tier = tier).copy(number = number, numberChosen = true).problem()

        assertNull(problem(FamilyTier.Tots, 33))
        assertNull(problem(FamilyTier.Kids, 34))
        assertNull(problem(FamilyTier.Kids, 66))
        assertNull(problem(FamilyTier.Teens, 67))
        assertNull(problem(FamilyTier.Teens, 99))
        assertNull(problem(null, 100))
        assertEquals(DraftProblem.KidsNumberOutsideBand, problem(FamilyTier.Tots, 34))
        assertEquals(DraftProblem.TierNumberOutsideBand, problem(FamilyTier.Kids, 33))
        assertEquals(DraftProblem.TierNumberOutsideBand, problem(FamilyTier.Kids, 67))
        assertEquals(DraftProblem.TierNumberOutsideBand, problem(FamilyTier.Teens, 66))
        assertEquals(DraftProblem.TierNumberOutsideBand, problem(FamilyTier.Teens, 100))
        assertEquals(DraftProblem.GrownUpNumberInKidsBand, problem(null, 1))
        assertEquals(DraftProblem.GrownUpNumberInKidsBand, problem(null, 33))
        assertEquals(DraftProblem.GrownUpNumberInYoungBand, problem(null, 34))
        assertEquals(DraftProblem.GrownUpNumberInYoungBand, problem(null, 66))
        assertEquals(DraftProblem.GrownUpNumberInYoungBand, problem(null, 99))
    }

    @Test
    fun aNumberTheBuilderFoundIsNeverAProblem() {
        assertNull(draft(tier = FamilyTier.Teens).copy(number = 5, numberChosen = false).problem())
    }
}
