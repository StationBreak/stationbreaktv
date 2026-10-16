// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibrarySource
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.testing.SyntheticLibrary
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import java.lang.ref.WeakReference
import kotlin.time.Instant

/**
 * Spec 06 task 3.3 (Goal 1): the generated 15,000-item library syncs through the engine's paging into the real database
 * with less than 64 MB of heap growth, and no more than one page is held at a time. The same test runs on the API 25
 * emulator (`FirstSyncHeapOnDeviceTest`). The library is never held whole: it is generated page by page.
 */
class FirstSyncHeapTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    /** The synthetic library as a server source that remembers each page it handed out, weakly. */
    private class WatchedSource(
        private val library: SyntheticLibrary,
    ) : LibrarySource {
        val pages = mutableListOf<WeakReference<List<MediaItem>>>()

        override val size: Int get() = library.size

        override fun page(
            startIndex: Int,
            limit: Int,
        ): List<MediaItem> = library.page(startIndex, limit).also { pages += WeakReference(it) }

        override fun savedAt(item: MediaItem): Instant =
            library.savedAt(
                item.id.value
                    .drop(1)
                    .toInt(HEX),
            )
    }

    private fun usedHeap(): Long {
        repeat(GCS) {
            System.gc()
            System.runFinalization()
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun fifteenThousandItemsSyncWithLessThan64MbOfHeapGrowthAndOnePageAtATime() =
        runBlocking {
            val library = SyntheticLibrary(size = 15_000)
            val source = WatchedSource(library)
            val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to source))
            repository.useAccount(ALICE)
            val baseline = usedHeap()
            var peak = baseline
            var mostPagesAlive = 0
            server.respond = { call ->
                if (!call.idsOnly) {
                    peak = maxOf(peak, usedHeap())
                    mostPagesAlive = maxOf(mostPagesAlive, source.pages.count { it.get() != null })
                }
                null
            }

            val engine = SyncWorld.engine(repository, server, clock, MediaServer.MAX_PAGE_SIZE)
            val outcome = engine.run(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(15_000, repository.rowCounts().items)
            val growthMb = (peak - baseline) / MB
            val numbers = "baseline ${baseline / KB} KB, peak ${peak / KB} KB, growth ${(peak - baseline) / KB} KB"
            println("HEAP on the JVM: $numbers; pages alive at most: $mostPagesAlive")
            assertTrue("heap grew by $growthMb MB, the limit is $LIMIT_MB", growthMb < LIMIT_MB)
            // The page just written may still be referenced when the next one is asked for; two never are.
            assertTrue("$mostPagesAlive pages were alive at once", mostPagesAlive <= 1)
        }

    private companion object {
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val HEX = 16
    }
}
