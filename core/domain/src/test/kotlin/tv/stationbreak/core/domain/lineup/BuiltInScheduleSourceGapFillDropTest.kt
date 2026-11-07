// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A piece whose row is gone by the time it is named: where the other pieces sit does not move. */
class BuiltInScheduleSourceGapFillDropTest {
    /** Leaves [dropped] out of the names of the second `programInfo` read after [arm] (the pieces' read). */
    private class FlakyLibrary(
        val inner: CountingLibrary,
    ) : LibraryRepository by inner {
        var dropped: CanonicalId? = null
        private var reads = 0

        fun arm(id: CanonicalId) {
            dropped = id
            reads = 0
        }

        override suspend fun programInfo(
            account: LibraryAccount,
            ids: Set<CanonicalId>,
        ): Map<CanonicalId, ProgramInfo> {
            val names = inner.programInfo(account, ids)
            reads++
            return if (reads == 2) names.filterKeys { it != dropped } else names
        }
    }

    private val minute = 60_000L
    private val counting = CountingLibrary()
    private val library = FlakyLibrary(counting)
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }
    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))

    // Items 100..105 run 60 minutes, 106..111 run 10: a 60-minute block that is gone is filled by 10-minute pieces.
    private val ids = (0 until 12).map { id(100 + it) }

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        val runtimes = ids.indices.map { (if (it < 6) 60 else 10) * minute }
        counting.seed(runtimes.mapIndexed { i, ms -> movie(100 + i, "Comedy", rating = "R", runtimeMs = ms) })
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    private fun blocks() = checkNotNull(runBlocking { source.schedule(NOON, SIX_HOURS, ScheduleBuild.AsNeeded) }).row()

    private fun ScheduleWindow.row() = rows.single().blocks

    private companion object {
        val SIX_HOURS: Instant = NOON + 6.hours
    }

    @Test
    fun aDroppedPiece_isAnUnnamedSpan_andTheOthersKeepTheirPlaces() {
        val gone = checkNotNull(runBlocking { source.onAir(laughs.id, NOON, 0) }).now
        val goneId = assertIs<Airing.Program>(gone.airing).itemId
        assertEquals(60 * minute, gone.endMs - gone.startMs, "the fixture puts a long programme on air")
        runBlocking { counting.delegate.seed(ACCOUNT, LIBRARIES.first(), *ids.drop(6).map(::row).toTypedArray()) }

        fun span() = blocks().filter { it.startMs >= gone.startMs && it.endMs <= gone.endMs }
        val whole = span()
        assertTrue(whole.size >= 3 && whole.all { assertIs<Airing.Program>(it.airing).info != null }, "$whole")
        val victim = whole[1]
        library.arm(assertIs<Airing.Program>(victim.airing).itemId)

        val cells = span()

        assertEquals(whole.map { it.startMs to it.endMs }, cells.map { it.startMs to it.endMs }, "no piece moves")
        cells.forEachIndexed { i, cell ->
            if (i == 1) {
                val program = assertIs<Airing.Program>(cell.airing)
                assertNull(program.info)
                assertEquals(goneId, program.itemId)
                val goneStart = assertIs<Airing.Program>(gone.airing).mediaStartMs
                assertEquals(goneStart + (cell.startMs - gone.startMs), program.mediaStartMs)
            } else {
                assertEquals(whole[i], cell)
            }
        }
    }

    private fun row(id: CanonicalId) = MediaItem(ItemId(id.value), "Test Movie", ItemKind.Movie, null, null)
}
