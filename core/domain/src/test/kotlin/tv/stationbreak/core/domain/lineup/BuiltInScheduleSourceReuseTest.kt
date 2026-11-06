// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ItemId
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #589: the build for a changed lineup takes over the days of the earlier build that the change leaves alone, from a
 * library that has not changed, and builds the rest.
 */
class BuiltInScheduleSourceReuseTest {
    private val counting = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val builds = Collections.synchronizedList(mutableListOf<DayBuild>())

    // The catalog the service plans with: catalog 2 until a test swaps it.
    private class Swappable(
        var current: StarterCatalogView,
    ) : StarterCatalogView {
        override val version get() = current.version
        override val recipes get() = current.recipes
        override val leftovers get() = current.leftovers
        override val retiredNumbers get() = current.retiredNumbers
        override val retiredKeys get() = current.retiredKeys
        override val selectsByIdentity get() = current.selectsByIdentity
    }

    private val catalog =
        Swappable(
            object : StarterCatalogView by TestCatalog {
                override val version: Int = 2
            },
        )
    private val service = LineupService(store, counting, clock, { 0 }, {}, catalog)
    private val chosen: Set<ItemId> = LIBRARIES
    private val source = BuiltInScheduleSource(service, counting, built = { builds += it }) { chosen }

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        counting.seed(fillingLibrary())
    }

    private fun refresh() = runBlocking { service.refresh(ACCOUNT, LIBRARIES) }

    private fun days() = runBlocking { source.channelDays(ACCOUNT, chosen, NOON) }

    private fun switchOff(key: String) {
        assertIs<Saved.Ok<*>>(runBlocking { service.edit(ACCOUNT, LineupEdit.SwitchOff(LineupIds.starter(key))) })
    }

    // The days a build with nothing to take over gives the same lineup: a source of its own.
    private fun fullBuild(): List<ChannelDay> {
        val fresh = BuiltInScheduleSource(service, counting) { chosen }
        return runBlocking { fresh.channelDays(ACCOUNT, chosen, NOON) }
    }

    @Test
    fun anEditThatMovesOneChannelBuildsOnlyThatChannelAndWhatClaimsFromIt() {
        refresh()
        days()
        switchOff("laughs")
        val second = days()

        val first = builds.first()
        val last = builds.last()
        assertEquals(0, first.reused, "the first build had nothing to take over")
        assertTrue(last.reused > 0, "the second took over the days the edit left alone: ${last.reused}")
        assertTrue(last.evaluations < first.evaluations, "${last.evaluations} < ${first.evaluations}")
        val expected = fullBuild()
        assertEquals(expected.map { it.channel.id }, second.map { it.channel.id })
        for (day in expected) {
            assertEquals(day.aired(), second.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        }
    }

    @Test
    fun aChangeToTheLibraryTakesNothingOver() {
        refresh()
        days()
        counting.seed(movie(900, "Comedy", rating = "R"))
        refresh()
        switchOff("laughs")
        days()

        assertEquals(0, builds.last().reused, "the content generation moved: every day is built")
    }

    // The upgrade launch of #589: the days are built for the installed catalog, the refresh then swaps in catalog 3,
    // and only what the swap really changes for today is built again.
    @Test
    fun theBuildAfterACatalogSwapTakesOverTheDaysOfTheChannelsAlreadyOnTheAir() {
        refresh()
        days()
        catalog.current = programmedCatalog(3, selects = true)
        refresh()
        val second = days()

        assertEquals(2, builds.size, "a build for each lineup")
        val last = builds.last()
        assertTrue(last.reused > 0, "the swap left today of the channels on the air alone: ${last.reused}")
        val expected = fullBuild()
        assertEquals(expected.map { it.channel.id }, second.map { it.channel.id })
        for (day in expected) {
            assertEquals(day.aired(), second.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        }
    }
}
