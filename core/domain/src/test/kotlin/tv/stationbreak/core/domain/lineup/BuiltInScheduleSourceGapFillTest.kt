// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The gap fill where the source names blocks (spec 26, #633): the same pieces on air and in a window. */
class BuiltInScheduleSourceGapFillTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }
    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val minute = 60_000L
    private val comedyIds = (0 until 7).map { id(100 + it) }

    // Seven comedies of 20, 25, ... 50 minutes. A crime film of 15 minutes is in the library but not in the channel's
    // pool.
    private fun minutesOf(index: Int) = 20L + 5 * index

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(
            comedyIds.indices.map { movie(100 + it, "Comedy", rating = "R", runtimeMs = minutesOf(it) * minute) },
        )
        library.seed(movie(150, "Crime", rating = "R", runtimeMs = 15 * minute))
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    /** The longest other pool item that is shorter than [block]: the one named in the tests of a remainder. */
    private fun shorterThan(
        block: AiringBlock,
        gone: CanonicalId,
    ): CanonicalId? =
        comedyIds
            .withIndex()
            .filter { (i, id) -> id != gone && minutesOf(i) * minute < block.endMs - block.startMs }
            .maxByOrNull { it.index }
            ?.value

    private fun name(ids: List<CanonicalId>) {
        val items = ids.map { MediaItem(ItemId(it.value), "Test Movie", ItemKind.Movie, null, null) }
        library.delegate.seed(ACCOUNT, LIBRARIES.first(), *items.toTypedArray())
    }

    private fun onAir(
        next: Int = 3,
        at: Instant = NOON,
    ) = checkNotNull(runBlocking { source.onAir(laughs.id, at, next) })

    private fun window(until: Instant = NOON + 6.hours) =
        checkNotNull(runBlocking { source.schedule(NOON, until, ScheduleBuild.AsNeeded) }).rows.single().blocks

    private fun List<AiringBlock>.programs() = mapNotNull { it.airing as? Airing.Program }

    private fun goneProgram(block: AiringBlock) = assertIs<Airing.Program>(block.airing).itemId

    @Test
    fun goneBlock_onAirAndScheduleShowTheSameFill() {
        val gone = onAir().now
        val goneId = goneProgram(gone)
        name((comedyIds - goneId) + id(150))

        val live = onAir()
        val cells = window().dropWhile { it.endMs <= NOON.toEpochMilliseconds() }

        assertTrue(goneProgram(live.now) != goneId || live.now.startMs > gone.startMs, "the gone item is replaced")
        val filled = listOf(live.now) + live.next
        assertEquals(filled, cells.take(filled.size), "on air and the window name the same pieces")
        val inGone = cells.filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs }
        assertTrue(inGone.programs().any { it.info != null }, "something named fills the gone block")
        val onAirSpan = filled.filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs }
        assertEquals(onAirSpan, inGone.dropWhile { it.endMs <= live.now.startMs }.take(onAirSpan.size))
        assertTrue(cells.none { (it.airing as? Airing.Program)?.let { p -> p.itemId == id(150) } == true })
    }

    @Test
    fun fillThatDoesNotCover_leavesUnnamedRemainder() {
        val gone = onAir().now
        val goneId = goneProgram(gone)
        val piece = shorterThan(gone, goneId)
        name(listOfNotNull(piece))
        val pieceMs = piece?.let { minutesOf(comedyIds.indexOf(it)) * minute } ?: 0L

        val cells = window().filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs }

        val rest = cells.last()
        val program = assertIs<Airing.Program>(rest.airing)
        assertEquals(goneId, program.itemId)
        assertNull(program.info)
        assertEquals(gone.startMs + pieceMs, rest.startMs)
        assertEquals(gone.endMs, rest.endMs)
        assertEquals(assertIs<Airing.Program>(gone.airing).mediaStartMs + pieceMs, program.mediaStartMs)
        val before = cells.dropLast(1)
        assertEquals(if (piece == null) 0 else 1, before.size)
        before.forEach {
            assertEquals(gone.startMs, it.startMs)
            assertEquals(gone.startMs + pieceMs, it.endMs)
            assertEquals(piece, assertIs<Airing.Program>(it.airing).itemId)
            assertEquals(0L, assertIs<Airing.Program>(it.airing).mediaStartMs)
            assertNotNull(assertIs<Airing.Program>(it.airing).info)
        }
    }

    @Test
    fun nothingNamed_leavesTheBlockAsItWasOneUnnamedProgram() {
        val cells = window()

        assertTrue(cells.isNotEmpty())
        assertTrue(cells.programs().all { it.info == null })
        cells.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun ordinaryWindow_noExtraReads() {
        name(comedyIds)
        window()
        library.programInfoCalls = 0
        library.currentIdsCalls = 0

        val cells = window()

        assertTrue(cells.programs().all { it.info != null })
        assertEquals(1, library.programInfoCalls)
        assertEquals(0, library.currentIdsCalls)
        library.programInfoCalls = 0
        onAir()
        assertEquals(1, library.programInfoCalls)
        assertEquals(0, library.currentIdsCalls)
    }

    @Test
    fun fillUsesTheDayPool_andFillExclusions() {
        val goneId = goneProgram(onAir().now)
        library.currentIdsCalls = 0
        // A pool item that has a row, one that has none, and a library item outside the channel's pool.
        val others = comedyIds - goneId
        val piece = others.last()
        name(listOf(piece, id(150)))

        val cells = window()
        val items = cells.programs()

        val filledWith = items.filter { it.info != null }.map { it.itemId }.toSet()
        assertEquals(setOf(piece), filledWith, "only a pool item with a current row, and it is used")
        assertTrue(items.none { it.itemId == id(150) }, "an item outside the day pool is never picked")
        assertNotNull(items.firstOrNull { it.info == null }, "what is not covered stays unnamed")
        assertEquals(1, library.currentIdsCalls, "one liveness read over the pool")
    }

    /** The window with every other item named, so they fill the gone block: its pieces, then what follows. */
    private fun fillWindow(): Pair<List<AiringBlock>, List<AiringBlock>> {
        val gone = onAir().now
        name((comedyIds - goneProgram(gone)) + id(150))
        val cells = window()
        return cells.filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs } to
            cells.filter { it.startMs >= gone.endMs }
    }

    @Test
    fun onAirInTheSecondPieceOfAGoneBlock_isThatPieceAndTheRestFollow() {
        val (pieces, after) = fillWindow()
        assertTrue(pieces.size >= 2, "the fill leaves a second piece: ${pieces.size}")
        val second = pieces[1]
        val at = Instant.fromEpochMilliseconds(second.startMs + minute)

        val live = onAir(next = 2, at = at)

        assertEquals(second, live.now)
        assertEquals(2, live.next.size)
        assertEquals((pieces.drop(2) + after).take(2), live.next)
    }

    @Test
    fun onAirExactlyOnAPieceBoundary_isThePieceThatStartsThere() {
        val (pieces, after) = fillWindow()
        assertTrue(pieces.size >= 2, "the fill leaves a second piece: ${pieces.size}")
        val boundary = pieces[0].endMs

        val live = onAir(next = 2, at = Instant.fromEpochMilliseconds(boundary))

        assertEquals(pieces[1], live.now)
        assertEquals(boundary, live.now.startMs)
        assertEquals(2, live.next.size)
        assertEquals((pieces.drop(2) + after).take(2), live.next)
    }
}
