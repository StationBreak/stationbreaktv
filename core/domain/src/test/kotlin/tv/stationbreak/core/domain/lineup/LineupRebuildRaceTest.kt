// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.rules.Source
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A day build that began before the rebuild drops the days must not put its old days on disk when it ends after the
 * drop (#812). The build's days key does not hold the lineup revision, so a build under the old revision is still
 * wanted by the new one: only the forget's epoch tells it apart. The build is held just after its days are made, before
 * it keeps and saves them, which is where the drop lands.
 */
class LineupRebuildRaceTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val lineups = RecordingStore()
    private val disk = MemoryDayStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val daysMade = CountDownLatch(1)
    private val rebuilt = CountDownLatch(1)
    private val probeCalls = AtomicInteger()

    // The days on disk are dropped inside the rebuild, before it publishes the new revision: the old build, still the
    // current revision to itself, is let go there and given time to save. What it wrote by the time the drop returns.
    @Volatile private var writesWhenTheDropReturned = -1
    private val derived =
        object : DayStore by disk {
            // The rebuild lists the files to delete: the first listing after the build is held is the drop.
            override suspend fun names(account: LibraryAccount): List<String> {
                val listed = disk.names(account)
                if (daysMade.count == 0L && rebuilt.count == 1L) {
                    rebuilt.countDown()
                    delay(SETTLE_MS)
                    writesWhenTheDropReturned = disk.writes
                }
                return listed
            }
        }
    private val service = LineupService(lineups, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = derived))

    // The build reads this clock before its days (call 1) and after them (call 2): it waits there, once.
    private val probes =
        BuildProbes(
            threadCpuNanos = {
                if (probeCalls.incrementAndGet() == 2) {
                    daysMade.countDown()
                    check(rebuilt.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the rebuild did not run" }
                }
                -1L
            },
        )
    private val source =
        BuiltInScheduleSource(
            service,
            library,
            probes = probes,
            persisted = PersistedDays(disk, "1:100", {}),
        ) { selectionOf(LIBRARIES) }

    @Test
    @Suppress("InjectDispatcher") // A caller on its own thread: the build is held while the test thread rebuilds.
    fun aBuildThatEndsDuringTheRebuildWritesNoDays() =
        runBlocking<Unit> {
            library.delegate.useAccount(ACCOUNT)
            val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            lineups.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs)))
            // Planned once already, so the rebuild changes no channel and the build's days key stays the lineup's.
            service.refresh(ACCOUNT, LIBRARIES)

            val caller = async(Dispatchers.Default) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            assertTrue(daysMade.await(WAIT_SECONDS, TimeUnit.SECONDS), "the build made its days")

            // The rebuild drops the days (letting the old build go at that moment) and then publishes a new revision.
            assertTrue(service.rebuildToday(ACCOUNT, LIBRARIES))
            caller.await()
            source.awaitSaves()

            assertEquals(0, writesWhenTheDropReturned, "the build the rebuild overtook wrote its days after the drop")
            assertEquals(0, disk.writes, "and none after it either, once the saves have run")
            assertTrue(disk.files.isEmpty())
        }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val SETTLE_MS = 500L
    }
}
