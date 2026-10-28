// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The kids' rating ceiling at schedule time never comes from the stored lineup alone (spec 12, security): the catalog
 * and the Kids band's PG maximum win over what the file says.
 */
class LineupCeilingTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }

    private fun scheduledPool(
        lineup: Lineup,
        channel: LineupChannel,
    ): Set<CanonicalId> =
        runBlocking {
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            source
                .channelDays(ACCOUNT, LIBRARIES, NOON)
                .first { it.channel.id == channel.id }
                .pool
                .mapTo(HashSet()) { it.id }
        }

    private fun seedRatings() {
        library.seed(
            movie(1, "Animation", "Kids", rating = "G"),
            movie(2, "Animation", "Kids", rating = "PG"),
            movie(3, "Animation", "Kids", rating = "R"),
            movie(4, "Animation", "Kids", rating = "TV-MA"),
        )
    }

    private val toons = rules("Toons", Source.Genre("Animation"))

    private fun editedToons(maxRating: String?): LineupChannel {
        val rules = rules("Toons", Source.Genre("Animation"), maxRating = maxRating)
        val edited = ChannelSource.Starter("toons", edited = true, rules = rules)
        return channel(LineupIds.starter("toons").value, 10, ChannelState.On, edited)
    }

    @Test
    fun storedNullKidsLeftoversCeilingStillSchedulesAtPgOrBelow() {
        seedRatings()
        val kids = leftovers("kids-odds-and-ends", 99, maxRating = null)
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(kids), kids))
    }

    @Test
    fun theCatalogCeilingWinsOverAStoredHigherLeftoversCeiling() {
        seedRatings()
        val kids = leftovers("kids-odds-and-ends", 99, maxRating = "R")
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(kids), kids))
    }

    @Test
    fun aRetiredLeftoversKeyKeepsItsStoredCeiling() {
        seedRatings()
        val retired = leftovers("grown-ups-retired", 150, maxRating = "R")
        assertEquals(setOf(id(1), id(2), id(3)), scheduledPool(lineupOf(retired), retired))
    }

    @Test
    fun aRetiredLeftoversKeyInTheKidsBandIsHeldToPg() {
        seedRatings()
        val retired = leftovers("kids-retired", 98, maxRating = null)
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(retired), retired))
    }

    @Test
    fun anUneditedKidsStarterStoredWithNoCeilingSchedulesAtTheRecipeCeiling() {
        seedRatings()
        val unedited = starter("toons", 10, ChannelState.On, toons)
        assertEquals(setOf(id(1)), scheduledPool(lineupOf(unedited), unedited), "the recipe's ceiling is G")
    }

    @Test
    fun anEditedKidsStarterStoredAbovePgSchedulesAtPg() {
        seedRatings()
        val edited = editedToons("R")
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(edited), edited))
    }

    @Test
    fun anEditedKidsStarterKeepsALowerStoredCeiling() {
        seedRatings()
        val edited = editedToons("G")
        assertEquals(setOf(id(1)), scheduledPool(lineupOf(edited), edited))
    }

    @Test
    fun anEditedKidsStarterWithNoStoredCeilingIsHeldToPg() {
        seedRatings()
        val edited = editedToons(null)
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(edited), edited))
    }

    @Test
    fun anyChannelNumberedInTheKidsBandIsHeldToPg() {
        seedRatings()
        val custom = channel("c1".padEnd(32, '0'), 50, ChannelState.On, ChannelSource.Custom(toons))
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(custom), custom))
    }

    @Test
    fun aGrownUpStarterOutsideTheKidsBandKeepsItsStoredRules() {
        seedRatings()
        val grownUp = starter("laughs", 110, ChannelState.On, toons)
        assertEquals(setOf(id(1), id(2), id(3), id(4)), scheduledPool(lineupOf(grownUp), grownUp))
    }

    @Test
    fun aRetiredKidsKeyRenumberedOutOfTheKidsBandIsHeldToPg() {
        seedRatings()
        val key = StarterCatalog.recipes.first { it.maxRating != null }.key
        val retired = starter(key, 150, ChannelState.On, toons)
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(retired), retired))
    }

    @Test
    fun aCurrentKidSafeStarterRenumberedOutOfTheKidsBandKeepsItsRecipeCeiling() {
        seedRatings()
        val moved = starter("toons", 150, ChannelState.On, toons)
        assertEquals(setOf(id(1)), scheduledPool(lineupOf(moved), moved), "the recipe's ceiling is G")
    }

    @Test
    fun kidsLeftoversRenumberedOutOfTheKidsBandKeepsItsCatalogCeiling() {
        seedRatings()
        val moved = leftovers("kids-odds-and-ends", 150, maxRating = null)
        assertEquals(setOf(id(1), id(2)), scheduledPool(lineupOf(moved), moved))
    }
}
