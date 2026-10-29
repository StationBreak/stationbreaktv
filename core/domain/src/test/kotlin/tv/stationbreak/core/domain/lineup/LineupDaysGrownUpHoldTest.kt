// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A grown-up starter drops children's-rated items (ADR-0020); a custom channel and the grown-up leftovers do not. Each
 * test has a TV-Y7 item and a PG item, both matching a genre.
 */
class LineupDaysGrownUpHoldTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }

    private val childrensItem = id(1)
    private val pgItem = id(2)

    private fun seed() {
        library.seed(movie(1, "Drama", rating = "TV-Y7"), movie(2, "Drama", rating = "PG"))
    }

    private fun days(lineup: Lineup): Map<String, ChannelDay> =
        runBlocking {
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            source.channelDays(ACCOUNT, LIBRARIES, NOON).associateBy { it.channel.id.value }
        }

    private fun ChannelDay.ids(): Set<CanonicalId> = pool.mapTo(HashSet()) { it.id }

    private fun drama() = rules("Drama", Source.Genre("Drama"))

    private fun starterAt(
        key: String,
        number: Int,
        edited: Boolean = false,
    ): LineupChannel {
        val source = ChannelSource.Starter(key, edited, drama())
        return channel(LineupIds.starter(key).value, number, ChannelState.On, source)
    }

    @Test
    fun aGrownUpStarterDropsTheChildrensRatedItem() {
        seed()
        val plain = starterAt("drama", 150)
        val edited = starterAt("drama-two", 160, edited = true)
        val days = days(lineupOf(plain, edited))
        assertEquals(setOf(pgItem), days.getValue(plain.id.value).ids())
        assertEquals(setOf(pgItem), days.getValue(edited.id.value).ids())
    }

    @Test
    fun aCustomChannelStillAirsTheChildrensRatedItem() {
        seed()
        val custom = channel("custom-out", 150, ChannelState.On, ChannelSource.Custom(drama()))
        assertEquals(setOf(childrensItem, pgItem), days(lineupOf(custom)).getValue("custom-out").ids())
    }

    @Test
    fun aGrownUpStarterWhoseOnlyMatchesAreChildrensRatedIsAllExcludedNotNoMatches() {
        library.seed(movie(1, "Drama", rating = "TV-Y"))
        val drama = starterAt("drama", 150)
        assertEquals(HiddenReason.AllExcluded, days(lineupOf(drama)).getValue(drama.id.value).hidden)
    }

    @Test
    fun theGrownUpLeftoversAirsWhatTheGrownUpStarterDropped() {
        seed()
        val drama = starterAt("drama", 150)
        val odds = leftovers("odds-and-ends", 199, null)
        assertEquals(setOf(childrensItem), days(lineupOf(drama, odds)).getValue(odds.id.value).ids())
    }

    @Test
    fun airingNowhereIsZeroWithBothLeftoversOn() =
        runBlocking<Unit> {
            seed()
            val lineup =
                lineupOf(
                    starterAt("drama", 150),
                    leftovers("kids-odds-and-ends", 99, "PG"),
                    leftovers("odds-and-ends", 199, null),
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
        }
}
