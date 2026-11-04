// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What has become past is rolled in the background, once per boundary, without ever making a read wait (spec 23, D1).
 */
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
                        if (it.id == laughs && source is ChannelSource.Starter) {
                            it.copy(source = source.withPrevious(old, dueMs), leavesAtMs = dueMs)
                        } else {
                            it
                        }
                    },
                curationBasis = CurationBasis(dueMs, emptySet(), true),
            )
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(due, stored.meta)) }
        return due
    }

    private fun pastDue() {
        now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 7_200_000L)
    }

    private fun savedLineup(): Lineup {
        val stored = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT)))
        return (stored as LineupCodec.Decoded.Loaded).lineup
    }

    @Test
    fun aLookupRollsWhatIsPastInTheBackgroundAndSavesIt() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            val rolled = checkNotNull(service.lineup(ACCOUNT).value).lineup
            val laughsNow = rolled.channels.first { it.id == laughs }
            assertEquals(ChannelState.Shelf, laughsNow.state)
            assertNull(laughsNow.leavesAtMs)
            assertNull((laughsNow.source as ChannelSource.Starter).previousRules)
            assertNull(rolled.curationBasis)
            assertEquals(rolled, savedLineup())
            // Nothing is past now: another lookup starts nothing.
            val attempts = service.rollAttempts.get()
            service.rollSoon(ACCOUNT, checkNotNull(service.lineup(ACCOUNT).value))
            assertEquals(attempts, service.rollAttempts.get())
        }

    @Test
    fun aLookupBeforeTheBoundaryChangesNothing() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(0, service.rollAttempts.get())
            assertEquals(due, checkNotNull(service.lineup(ACCOUNT).value).lineup)
        }

    @Test
    fun aSessionThatCannotSaveMakesOneAttemptPerBoundary() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            store.inner.failWrites = true
            val saves = store.calls.count { it == "save" }
            repeat(25) { service.rollSoon(ACCOUNT, held) }
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get(), "however many lookups")
            repeat(25) { service.rollSoon(ACCOUNT, held) }
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get())
            assertEquals(saves + 1, store.calls.count { it == "save" }, "the failed save was tried once")
            // The next boundary is another attempt.
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 86_400_000L)
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(2, service.rollAttempts.get())
        }

    @Test
    fun aLookupNeverWaitsOnAHeldLock() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            val lock = checkNotNull(service.lockOf(ACCOUNT))
            lock.lock()
            try {
                service.rollSoon(ACCOUNT, held)
                service.awaitRolls()
            } finally {
                lock.unlock()
            }
            assertEquals(due, checkNotNull(service.lineup(ACCOUNT).value).lineup, "skipped: a refresh rolls anyway")
        }

    @Test
    fun concurrentLookupsAtTurnoverReadTheStoreNoMoreThanOnce() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            val loads = store.calls.count { it == "load" }
            (1..20).map { async { service.rollSoon(ACCOUNT, held) } }.awaitAll()
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get())
            assertEquals(loads, store.calls.count { it == "load" }, "a roll works from the held lineup")
        }
}
