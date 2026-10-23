// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BuiltInScheduleSourceOnAirTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private var chosen: Set<ItemId>? = LIBRARIES
    private val timings = mutableListOf<Pair<Long, Long>>()
    private val source = BuiltInScheduleSource(service, library, { day, ms -> timings += day to ms }) { chosen }

    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val dayStart = Instant.parse("2026-10-19T06:00:00Z")
    private val dayEnd = Instant.parse("2026-10-20T06:00:00Z")

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        holding(laughs)
    }

    private fun holding(vararg channels: LineupChannel) {
        val bytes = LineupCodec.encode(lineupOf(*channels))
        runBlocking { store.inner.save(ACCOUNT, bytes) }
    }

    private fun onAir(
        channel: ChannelId = laughs.id,
        at: Instant = NOON,
        next: Int = 2,
    ) = runBlocking { source.onAir(channel, at, next) }

    private fun nameTheFillingLibrary() {
        val names =
            (0 until 7).map {
                MediaItem(ItemId(id(100 + it).value), "Test Movie $it", ItemKind.Movie, null, null)
            }
        library.delegate.seed(ACCOUNT, LIBRARIES.first(), *names.toTypedArray())
    }

    @Test
    fun onAirGivesTheBlockAndThePositionAtTheMoment() {
        val at = NOON + 7.minutes
        val atMs = at.toEpochMilliseconds()
        val result = checkNotNull(onAir(at = at, next = 2))
        val day = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }.single().day
        val expected = day.whatsOn(atMs, 2)

        val program = assertIs<Airing.Program>(result.now.airing)
        assertEquals(expected.block.startMs, result.now.startMs)
        assertEquals(expected.block.endMs, result.now.endMs)
        assertEquals(checkNotNull(expected.offsetMs), result.now.positionAt(atMs)?.inWholeMilliseconds)
        val position = result.now.positionAt(atMs)?.inWholeMilliseconds
        assertEquals(program.mediaStartMs + (atMs - result.now.startMs), position)
        assertEquals(expected.next.map { it.startMs }, result.next.map { it.startMs })
        assertEquals(2, result.next.size)
    }

    @Test
    fun onAirOfASlateHasNoOffsetAndNoItem() {
        // 100-minute movies on a 30-minute grid leave gaps between programs.
        val gappy = (0 until 7).map { movie(110 + it, "Comedy", rating = "R", runtimeMs = 6_000_000L) }
        val fresh = CountingLibrary()
        runBlocking { fresh.delegate.useAccount(ACCOUNT) }
        fresh.seed(gappy)
        val gapService = LineupService(store, fresh, clock, { 0 }, {}, TestCatalog)
        val gapSource = BuiltInScheduleSource(gapService, fresh) { LIBRARIES }
        val day = runBlocking { gapSource.channelDays(ACCOUNT, LIBRARIES, NOON) }.single().day
        val slate = day.blocks.first { it.content is ScheduleContent.Slate }
        val at = Instant.fromEpochMilliseconds(slate.startMs)

        val result = checkNotNull(runBlocking { gapSource.onAir(laughs.id, at, 1) })

        assertEquals(Airing.Slate, result.now.airing)
        assertNull(result.now.positionAt(slate.startMs))
        assertEquals(1, result.next.size)
    }

    @Test
    fun onAirSaysHowFarAheadTheScheduleIsBuilt() {
        // A 24-hour day: the end of the day after the one holding the moment.
        assertEquals((dayEnd + 24.hours).toEpochMilliseconds(), checkNotNull(onAir()).builtUntilMs)

        // A six-hour channel: the moment is in the day that starts at 12:00, so day n + 1 ends at 00:00.
        val quick = rules("Quick", Source.Genre("Comedy"), timing = timing().copy(epochHours = 6))
        val sixHours = channel("custom-6h", 600, ChannelState.On, ChannelSource.Custom(quick))
        holding(laughs, sixHours)
        // The service holds the first lineup in memory, so the six-hour one is read by a fresh service.
        val quickService = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
        val fresh = BuiltInScheduleSource(quickService, library) { LIBRARIES }
        val quickAir = runBlocking { fresh.onAir(sixHours.id, NOON + 1.minutes, 1) }
        val expected = Instant.parse("2026-10-20T00:00:00Z").toEpochMilliseconds()
        assertEquals(expected, checkNotNull(quickAir).builtUntilMs)
    }

    @Test
    fun onAirNextCrossesIntoTheNextDay() {
        val at = dayEnd - 1.minutes
        val result = checkNotNull(onAir(at = at, next = 3))

        assertEquals(3, result.next.size)
        assertTrue(result.next.last().startMs >= dayEnd.toEpochMilliseconds())
        val all = listOf(result.now) + result.next
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "blocks run on without a gap") }
    }

    @Test
    fun firstTuneNearADayEdgeIsTimed() {
        onAir(at = NOON, next = 2)
        assertTrue(timings.isEmpty(), "no day was built ahead")

        onAir(at = dayEnd - 1.minutes, next = 3)

        val (dayIndex, ms) = timings.single()
        val today = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON) }.single()
        assertEquals(today.n + 1, dayIndex)
        assertTrue(ms >= 0)
    }

    @Test
    fun aDayAlreadyBuiltIsNotTimedAgain() {
        onAir(at = dayEnd - 1.minutes, next = 3)
        assertEquals(1, timings.size)

        onAir(at = dayEnd - 1.minutes, next = 3)
        onAir(at = dayEnd - 2.minutes, next = 3)

        assertEquals(1, timings.size, "a cache hit is not a build")
    }

    @Test
    fun onAirLooksNoFurtherThanTheEndOfTheNextDay() {
        // Ask for far more blocks than two days hold: the answer stops at the end of day n + 1.
        val result = checkNotNull(onAir(at = NOON, next = 200))

        assertTrue(result.next.last().endMs <= result.builtUntilMs, "no block past builtUntilMs")
        assertEquals(1, timings.size, "only day n + 1 was built ahead")
    }

    @Test
    fun onAirOfAHiddenOrUnknownChannelIsNull() {
        val frights = starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Nothing like it")))
        val shelved = starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Genre("Comedy")))
        holding(laughs, frights, shelved)

        assertNotNull(onAir(laughs.id))
        assertNull(onAir(frights.id), "hidden today")
        assertNull(onAir(shelved.id), "not switched on")
        assertNull(onAir(ChannelId("no-such-channel")))
        chosen = null
        assertNull(onAir(laughs.id), "no libraries chosen")
    }

    @Test
    fun onAirBlocksCarryProgramInfo() {
        nameTheFillingLibrary()
        val result = checkNotNull(onAir(next = 3))

        for (block in listOf(result.now) + result.next) {
            val program = assertIs<Airing.Program>(block.airing)
            val info = assertNotNull(program.info)
            assertEquals(program.itemId, info.id)
            assertTrue(info.title.startsWith("Test Movie "))
        }
        assertEquals(1, library.programInfoCalls, "one read for all the blocks")
    }

    @Test
    fun onAirOfADeletedItemHasNoInfo() {
        val result = checkNotNull(onAir())

        val program = assertIs<Airing.Program>(result.now.airing)
        assertNull(program.info)
        assertTrue(program.itemId.value.isNotEmpty())
    }

    @Test
    fun onAirAgreesWithAirings() {
        val result = checkNotNull(onAir())

        assertEquals(runBlocking { source.airings(NOON) }.single(), result.channel)
        assertEquals(dayStart, result.channel.epochStart)
    }
}
