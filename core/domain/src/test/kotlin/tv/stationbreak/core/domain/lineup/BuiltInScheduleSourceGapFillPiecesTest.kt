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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * On-air expansion across two named fill pieces (spec 26, #646). [BuiltInScheduleSourceGapFillTest]'s gone block is
 * 25 minutes, so its fill is one named piece and an unnamed remainder: "inside the second piece" lands on the
 * remainder. Here one long item has left the library and the rest are short, so the fill is three named pieces.
 */
class BuiltInScheduleSourceGapFillPiecesTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }
    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val minute = 60_000L

    // One 45-minute comedy (the one that leaves the library) and six short ones. Greedy, longest first, the fill of the
    // long one's block is 15, 14 and 13 minutes, then 3 minutes nothing fits.
    private val longId = id(100)
    private val shortIds = (1..6).map { id(100 + it) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(movie(100, "Comedy", rating = "R", runtimeMs = 45 * minute))
        shortIds.indices.forEach {
            library.seed(movie(101 + it, "Comedy", rating = "R", runtimeMs = (10 + it) * minute))
        }
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    private fun name(ids: List<CanonicalId>) {
        val items = ids.map { MediaItem(ItemId(it.value), "Test Movie", ItemKind.Movie, null, null) }
        library.delegate.seed(ACCOUNT, LIBRARIES.first(), *items.toTypedArray())
    }

    private fun onAir(
        next: Int,
        at: Instant,
    ) = checkNotNull(runBlocking { source.onAir(laughs.id, at, next) })

    private fun window(until: Instant = NOON + 12.hours) =
        checkNotNull(runBlocking { source.schedule(NOON, until, ScheduleBuild.AsNeeded) }).rows.single().blocks

    private fun programOf(block: AiringBlock) = assertIs<Airing.Program>(block.airing)

    /** The long item's block, filled: its pieces, then what follows. The long item is not named, so it is gone. */
    private fun fillWindow(): Pair<List<AiringBlock>, List<AiringBlock>> {
        val gone = window().first { (it.airing as? Airing.Program)?.itemId == longId }
        assertEquals(45 * minute, gone.endMs - gone.startMs, "the long item airs whole")
        name(shortIds)
        val cells = window()
        return cells.filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs } to
            cells.filter { it.startMs >= gone.endMs }
    }

    @Test
    fun theFillOfALongGoneBlockIsSeveralNamedPieces() {
        val (pieces, _) = fillWindow()
        assertEquals(listOf(15L, 14L, 13L, 3L), pieces.map { (it.endMs - it.startMs) / minute })
        pieces.take(3).forEachIndexed { i, piece ->
            assertNotNull(programOf(piece).info, "piece ${i + 1} is named")
        }
        assertEquals(longId, programOf(pieces.last()).itemId, "the 3 minutes left stay an unnamed remainder")
    }

    @Test
    fun onAirInTheSecondNamedPiece_isThatPieceAndTheNextOnesFollow() {
        val (pieces, after) = fillWindow()
        val second = pieces[1]
        assertNotNull(programOf(second).info, "piece 2 is named, not the remainder")

        val live = onAir(next = 3, at = Instant.fromEpochMilliseconds(second.startMs + minute))

        assertEquals(second, live.now)
        assertEquals((pieces.drop(2) + after).take(3), live.next)
        assertNotNull(programOf(live.next.first()).info, "piece 3 is named too")
    }

    @Test
    fun onAirExactlyOnTheBoundaryBetweenTwoNamedPieces_isTheSecond() {
        val (pieces, after) = fillWindow()
        assertNotNull(programOf(pieces[0]).info, "piece 1 is named")
        assertNotNull(programOf(pieces[1]).info, "piece 2 is named")
        val boundary = pieces[0].endMs
        assertEquals(boundary, pieces[1].startMs)

        val live = onAir(next = 3, at = Instant.fromEpochMilliseconds(boundary))

        assertEquals(pieces[1], live.now)
        assertEquals(boundary, live.now.startMs)
        assertEquals((pieces.drop(2) + after).take(3), live.next)
    }

    @Test
    fun onAirTheMomentBeforeTheBoundary_isStillTheFirstPiece() {
        val (pieces, _) = fillWindow()
        val live = onAir(next = 1, at = Instant.fromEpochMilliseconds(pieces[0].endMs - 1))

        assertEquals(pieces[0], live.now)
        assertEquals(pieces[1], live.next.single())
        assertTrue(live.now.endMs > pieces[0].endMs - 1)
    }
}
