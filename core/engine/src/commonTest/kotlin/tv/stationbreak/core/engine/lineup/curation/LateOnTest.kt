// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a channel switched on after the basis airs until the boundary (`Curation.lateOn`, #776). */
class LateOnTest {
    private fun LateOnPool.titles(): Set<CanonicalId> = items.mapTo(HashSet()) { it.groupId }

    private fun idsOf(items: List<ScheduleItem>): List<CanonicalId> = items.map { it.id }

    @Test
    fun titlesNoBasisChannelHomesAreItsOwnAndTheRestAreGuests() {
        val lib = Library()
        val homedElsewhere = lib.show(eps = 3, genres = listOf("Drama"))
        val free = lib.show(eps = 3, genres = listOf("Western"))
        val basis = channel("ch-a", 100, listOf("Drama"), lib.itemsOf(listOf(homedElsewhere)))
        val late = channel("ch-l", 200, listOf("Drama", "Western"), lib.itemsOf(listOf(homedElsewhere, free)))
        val snap = lib.snapshot()

        val out = Curation.lateOn(snap, late, Curation.homes(snap, listOf(basis)))

        assertEquals(idsOf(late.pool), idsOf(out.items), "the whole pool, in its order")
        assertEquals(setOf(free), out.homed, "only the titles nobody homes are its own")
        assertEquals(2, out.weights[free])
        assertEquals(1, out.weights[homedElsewhere])
    }

    @Test
    fun aChannelWhoseEveryTitleIsHomedElsewhereIsNotEmpty() {
        val lib = Library()
        val shows = List(3) { lib.show(eps = 2, genres = listOf("Drama")) }
        val basis = channel("ch-a", 100, listOf("Drama"), lib.itemsOf(shows))
        val late = channel("ch-l", 200, listOf("Drama"), lib.itemsOf(shows))
        val snap = lib.snapshot()

        val out = Curation.lateOn(snap, late, Curation.homes(snap, listOf(basis)))

        assertEquals(shows.toSet(), out.titles())
        assertTrue(out.homed.isEmpty(), "it homes none of them")
        assertTrue(shows.all { out.weights[it] == 1 })
    }

    @Test
    fun withNoBasisEveryTitleIsItsOwn() {
        val lib = Library()
        val shows = List(2) { lib.show(eps = 2, genres = listOf("Drama")) }
        val late = channel("ch-l", 200, listOf("Drama"), lib.itemsOf(shows))

        val out = Curation.lateOn(lib.snapshot(), late, null)

        assertEquals(shows.toSet(), out.homed)
        assertTrue(shows.all { out.weights[it] == 2 })
        assertEquals(idsOf(late.pool), idsOf(out.items))
    }

    @Test
    fun aKidTitleNeverGuestsOnAGrownUpChannelButDoesOnAKidChannel() {
        val lib = Library()
        val kidShow = lib.show(eps = 2, genres = listOf("Kids"), rating = "TV-Y")
        val adultShow = lib.show(eps = 2, genres = listOf("Drama"))
        val kidBasis = channel("kid-a", 10, listOf("Kids"), lib.itemsOf(listOf(kidShow)), kid = true)
        val adultBasis = channel("ch-a", 100, listOf("Drama"), lib.itemsOf(listOf(adultShow)))
        val snap = lib.snapshot()
        val homes = Curation.homes(snap, listOf(kidBasis, adultBasis))
        val both = lib.itemsOf(listOf(kidShow, adultShow))

        val grownUp = Curation.lateOn(snap, channel("ch-l", 200, listOf("Drama"), both), homes)
        assertEquals(setOf(adultShow), grownUp.titles(), "the kid title stays off a grown-up channel")

        val kid = Curation.lateOn(snap, channel("kid-l", 11, listOf("Kids"), both, kid = true), homes)
        assertEquals(setOf(kidShow, adultShow), kid.titles(), "a kid channel airs its whole pool")
        assertEquals(1, kid.weights[kidShow])
    }

    @Test
    fun theAnimeRuleHoldsForGuests() {
        val lib = Library()
        val anime = lib.show(eps = 3, genres = listOf("Anime", "Drama"))
        val live = lib.show(eps = 3, genres = listOf("Drama"))
        val animeTheme = Theme(emptyList(), emptyList(), null, animeOnly = true)
        val grownUpBasis = channel("ch-g", 200, listOf("Drama"), lib.itemsOf(listOf(live)))
        val animeBasis = channel("ch-x", 300, listOf("Anime"), lib.itemsOf(listOf(anime)), theme = animeTheme)
        val snap = lib.snapshot()
        val homes = Curation.homes(snap, listOf(grownUpBasis, animeBasis))
        val both = lib.itemsOf(listOf(anime, live))

        val grownUp = Curation.lateOn(snap, channel("ch-l", 201, listOf("Drama"), both), homes)
        assertEquals(setOf(live), grownUp.titles(), "a grown-up channel drops anime")

        val onlyAnime = Curation.lateOn(snap, channel("ch-y", 301, listOf("Anime"), both, theme = animeTheme), homes)
        assertEquals(setOf(anime), onlyAnime.titles(), "an anime-only channel keeps only anime")
        assertEquals(1, onlyAnime.weights[anime])
    }
}
