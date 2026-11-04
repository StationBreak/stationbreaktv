// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/** What has become past is rolled on a read and saved once, and only then (spec 23, D1). */
class LineupServiceRollTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private var now = NOON

    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun newService() = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private val laughs = LineupIds.starter("laughs")

    // A finalised lineup with a leaving starter, previous rules and a basis, all due at [dueMs], stored.
    private fun storeDue(dueMs: Long): Lineup {
        runBlocking { newService().refresh(ACCOUNT, LIBRARIES) }
        val stored = (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded)
        val old = rules("Old", Source.Genre("Crime"))
        val due =
            stored.lineup.copy(
                channels =
                    stored.lineup.channels.map {
                        val source = it.source
                        when {
                            it.id == laughs && source is ChannelSource.Starter -> {
                                it.copy(source = source.withPrevious(old, dueMs), leavesAtMs = dueMs)
                            }

                            else -> {
                                it
                            }
                        }
                    },
                curationBasis = CurationBasis(dueMs, emptySet(), true),
            )
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(due, stored.meta)) }
        return due
    }

    @Test
    fun aReadRollsWhatIsPastAndSavesIt() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 7_200_000L)
            val service = newService()
            val held = checkNotNull(service.loadedRolled(ACCOUNT))
            val laughsNow = held.lineup.channels.first { it.id == laughs }
            assertEquals(ChannelState.Shelf, laughsNow.state)
            assertNull(laughsNow.leavesAtMs)
            assertNull((laughsNow.source as ChannelSource.Starter).previousRules)
            assertNull(held.lineup.curationBasis)
            val storedNow = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT)))
            val saved = (storedNow as LineupCodec.Decoded.Loaded).lineup
            assertEquals(held.lineup, saved)
            // Read again: nothing is past, so nothing is saved and the revision stays.
            val saves = store.calls.count { it == "save" }
            val again = checkNotNull(service.loadedRolled(ACCOUNT))
            assertSame(held, again)
            assertEquals(saves, store.calls.count { it == "save" })
            assertEquals(ChannelState.On, due.channels.first { it.id == laughs }.state)
        }

    @Test
    fun aReadBeforeTheBoundaryChangesNothing() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            val service = newService()
            val saves = store.calls.count { it == "save" }
            val held = checkNotNull(service.loadedRolled(ACCOUNT))
            assertEquals(due, held.lineup)
            assertEquals(saves, store.calls.count { it == "save" })
        }
}
