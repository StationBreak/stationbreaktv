// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one description of a draft that the builder's edit sheet and a channel's properties both word. */
class RulesDescriptionTest {
    private fun draft(
        order: PlayOrder? = PlayOrder.Shuffle,
        kept: List<KeptItem> = emptyList(),
        seriesCap: Int? = ChannelDraft.DEFAULT_SERIES_CAP,
        filmCap: Int? = ChannelDraft.DEFAULT_FILM_CAP,
    ) = ChannelDraft(
        name = "Mine",
        sources = listOf(DraftSource.Genre("Comedy", "Comedy"), DraftSource.Show("s1", "Some Show")),
        leaveOut = listOf(DraftSource.Tag("late", "late")),
        filters = DraftFilters(genres = listOf("Drama"), yearFrom = 1990, yearTo = 1999, maxRating = "PG"),
        order = order,
        seriesCap = seriesCap,
        filmCap = filmCap,
        kids = false,
        kept = kept.takeIf { it.isNotEmpty() }?.let { KeptSettings("Copied", it) },
        seed = Seed(1uL),
    )

    @Test
    fun aDraftIsDescribedByItsSourcesLeaveOutNarrowingAndCeiling() {
        val description = draft().describe()

        assertEquals(listOf("Comedy", "Some Show"), description.sources.map { it.label })
        assertEquals(listOf("late"), description.leaveOut.map { it.label })
        assertEquals(listOf("Drama"), description.narrowing.genres)
        assertEquals(1990, description.narrowing.yearFrom)
        assertEquals("PG", description.ceiling)
        assertNull(description.narrowing.runtime)
    }

    @Test
    fun theOrderIsTheDraftsOwnElseTheKeptOneElseShuffle() {
        assertEquals(ShownOrder.Play(PlayOrder.Rotation), draft(order = PlayOrder.Rotation).describe().order)
        assertEquals(ShownOrder.Play(PlayOrder.Shuffle), draft().describe().order)

        val kept = listOf(KeptItem.Order(KeptOrder.Chronological))
        assertEquals(ShownOrder.Kept(KeptOrder.Chronological), draft(order = null, kept = kept).describe().order)
        // The viewer's own choice replaces the kept order.
        assertEquals(ShownOrder.Play(PlayOrder.InOrder), draft(order = PlayOrder.InOrder, kept = kept).describe().order)
    }

    @Test
    fun capsAreShownExceptForInOrderAndAKeptOrder() {
        val capped = draft().describe()
        assertTrue(capped.showsCaps)
        assertEquals(3, capped.seriesCap)
        assertEquals(1, capped.filmCap)

        assertTrue(!draft(order = PlayOrder.InOrder).describe().showsCaps)
        val keptOrder = draft(order = null, kept = listOf(KeptItem.Order(KeptOrder.Curated)))
        assertTrue(!keptOrder.describe().showsCaps)
    }

    @Test
    fun aKeptUncappedShuffleShowsNoLimit() {
        val uncapped = draft(order = null, kept = listOf(KeptItem.NoCaps), seriesCap = null, filmCap = null)

        val description = uncapped.describe()

        assertNull(description.seriesCap)
        assertNull(description.filmCap)
        assertEquals(listOf<KeptItem>(KeptItem.NoCaps), description.kept)
    }

    @Test
    fun aNarrowingWithOnlyAnUpperYearIsNotEmpty() {
        assertTrue(Narrowing().isEmpty)
        assertTrue(!Narrowing(yearTo = 1999).isEmpty)
    }
}
