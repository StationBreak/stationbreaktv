// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.rules.Source
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

    // The app's catalog from its first build on, as after an upgrade: catalog 3, with one more channel than catalog 2.
    private val catalog = programmedCatalog(3, selects = true)
    private val service = LineupService(store, counting, clock, { 0 }, {}, catalog)
    private val chosen: Set<ItemId> = LIBRARIES
    private val source = BuiltInScheduleSource(service, counting, built = { builds += it }) { chosen }

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        counting.seed(fillingLibrary())
    }

    // The lineup the previous version of the app left, planned with catalog 2.
    private fun holdingOlder() {
        // As the previous version wrote its rules: back to back (alignToGrid false), as the swap's previous rules say.
        fun written(
            name: String,
            vararg sources: Source,
            maxRating: String? = null,
        ) = rules(name, *sources, maxRating = maxRating).let { it.copy(timing = it.timing.copy(alignToGrid = false)) }
        val older =
            lineupOf(
                starter("toons", 10, ChannelState.On, written("Toons", Source.Genre("Animation"), maxRating = "G")),
                starter("laughs", 110, ChannelState.On, written("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, written("Whodunit", Source.Genre("Crime"))),
                starter("westerns", 220, ChannelState.On, written("Westerns", Source.Genre("Western"))),
                leftovers("kids-odds-and-ends", 99, "PG"),
                leftovers("odds-and-ends", 199, null),
            ).copy(catalogVersion = 2)
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(older)) }
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
        holdingOlder()
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
        holdingOlder()
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
        holdingOlder()
        val before = days()
        refresh()
        val second = days()
        assertTrue(before.isNotEmpty())

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
