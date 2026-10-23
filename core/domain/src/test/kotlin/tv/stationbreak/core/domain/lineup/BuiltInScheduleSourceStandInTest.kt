// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class BuiltInScheduleSourceStandInTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }
    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val comedyIds = (0 until 7).map { id(100 + it) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    private fun name(vararg numbers: Int) {
        val names = numbers.map { MediaItem(ItemId(id(it).value), "Test Movie $it", ItemKind.Movie, null, null) }
        library.delegate.seed(ACCOUNT, LIBRARIES.first(), *names.toTypedArray())
    }

    private fun dead(
        from: BuiltInScheduleSource = source,
        lengthMs: Long = TWO_HOURS_MS,
    ): AiringBlock {
        val now = checkNotNull(runBlocking { from.onAir(laughs.id, NOON, 0) }).now
        return now.copy(endMs = now.startMs + lengthMs)
    }

    private fun standIns(
        block: AiringBlock,
        exclude: Set<CanonicalId>,
        from: BuiltInScheduleSource = source,
        channel: ChannelId = laughs.id,
    ) = runBlocking { from.standIns(channel, block, exclude) }

    private fun itemOf(block: AiringBlock) = assertIs<Airing.Program>(block.airing)

    private fun standInOf(block: AiringBlock) = assertIs<Airing.StandIn>(block.airing)

    @Test
    fun standInsCoverTheBlockFromItsStart() {
        name(*(100..106).toList().toIntArray())
        val block = dead(lengthMs = 5 * 3_600_000L)
        val deadItem = itemOf(block).itemId

        val result = standIns(block, setOf(deadItem))

        assertEquals(3, result.size)
        assertEquals(block.startMs, result.first().startMs)
        assertEquals(block.endMs, result.last().endMs, "the last one is clipped to the block")
        result.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        for (virtual in result) {
            val standIn = standInOf(virtual)
            assertEquals(block.startMs, standIn.forBlockStartMs)
            assertNotNull(standIn.info)
            assertTrue(standIn.itemId != deadItem)
        }
        assertEquals(result.size, result.map { standInOf(it).itemId }.toSet().size, "no item twice")
        assertEquals(0L, virtualPosition(result.first(), block.startMs))
    }

    private fun virtualPosition(
        block: AiringBlock,
        atMs: Long,
    ) = checkNotNull(block.positionAt(atMs)).inWholeMilliseconds

    @Test
    fun standInsAreTheSameForTwoSourcesOnTheSameLibrary() {
        name(*(100..106).toList().toIntArray())
        val other = BuiltInScheduleSource(service, library) { LIBRARIES }
        val block = dead(lengthMs = 6 * 3_600_000L)
        val exclude = setOf(itemOf(block).itemId)

        assertEquals(standIns(block, exclude), standIns(block, exclude, from = other))
        assertTrue(standIns(block, exclude).isNotEmpty())
    }

    @Test
    fun standInsOfAnEmptyPickIsEmpty() {
        name(*(100..106).toList().toIntArray())
        val block = dead()

        assertEquals(emptyList(), standIns(block, comedyIds.toSet()))
        assertEquals(emptyList(), standIns(block, emptySet(), channel = ChannelId("no-such-channel")))
    }

    @Test
    fun standInsNeverPickAnItemGoneFromTheLibrary() {
        name(100, 101, 102)
        val block = dead(lengthMs = 12 * 3_600_000L)
        val exclude = setOf(itemOf(block).itemId)

        val picked = standIns(block, exclude).map { standInOf(it).itemId }

        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { it in setOf(id(100), id(101), id(102)) }, "only items with a current row")
    }

    @Test
    fun standInsRejectAStandInBlock() {
        name(*(100..106).toList().toIntArray())
        val block = dead(lengthMs = 5 * 3_600_000L)
        val first = standIns(block, setOf(itemOf(block).itemId)).first()

        assertFailsWith<IllegalArgumentException> { standIns(first, emptySet()) }
    }

    @Test
    fun standInsOfTheSameBlockWithAGrowingExcludeKeepsTheBlockStart() {
        name(*(100..106).toList().toIntArray())
        val block = dead(lengthMs = 5 * 3_600_000L)
        val deadItem = itemOf(block).itemId
        val first = standIns(block, setOf(deadItem))
        val failed = standInOf(first.first()).itemId

        val second = standIns(block, setOf(deadItem, failed))

        assertTrue(second.isNotEmpty())
        assertEquals(block.startMs, second.first().startMs)
        assertEquals(block.startMs, standInOf(second.first()).forBlockStartMs)
        assertTrue(second.none { standInOf(it).itemId in setOf(deadItem, failed) })
    }

    @Test
    fun standInsCheckLivenessWithOneNarrowReadAndNameOnlyThePicked() {
        name(*(100..106).toList().toIntArray())
        val block = dead(lengthMs = 5 * 3_600_000L)
        library.programInfoCalls = 0

        val result = standIns(block, setOf(itemOf(block).itemId))

        assertEquals(1, library.currentIdsCalls)
        assertEquals(1, library.programInfoCalls)
        assertTrue(result.isNotEmpty())
    }
}
