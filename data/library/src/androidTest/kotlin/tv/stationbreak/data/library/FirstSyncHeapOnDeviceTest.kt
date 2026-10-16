// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibrarySource
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.testing.SyntheticLibrary
import java.lang.ref.WeakReference
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Spec 06 task 3.10 (Goal 1): the heap test of the first sync on the API 25 emulator, over the platform SQLite that
 * Fire OS 6 has. The generated 15,000-item library goes through the engine's paging into the real database with less
 * than 64 MB of heap growth, and no more than one page is alive at a time. The same test runs on the JVM
 * (`FirstSyncHeapTest`). Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class FirstSyncHeapOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("heap-test-server"), UserId("heap-test-user"))
    private val movies = Library(ItemId("11111111111111111111111111111111"), "Movies", LibraryKind.Movies)
    private val session =
        Session(
            account.user,
            AccessToken("placeholder-token-for-the-heap-test"),
            ServerInfo("Placeholder", account.server, ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "jellyfin.example.test", 443),
            null,
            false,
        )
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun open() {
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
        repository = RoomLibraryRepository.create(context)
    }

    @After
    fun close() {
        repository.close()
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
    }

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
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun fifteenThousandItemsSyncWithLessThan64MbOfHeapGrowthAndOnePageAtATime() =
        runBlocking {
            val library = SyntheticLibrary(size = 15_000, libraryId = movies.id)
            val source = WatchedSource(library)
            val server = PagedMediaServer(listOf(movies), mapOf(movies.id to source))
            repository.useAccount(account)
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
            val engine = FirstSyncEngine(repository, server, Clock.System, MediaServer.MAX_PAGE_SIZE)

            val outcome = engine.run(session, account, setOf(movies.id))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(15_000, repository.rowCounts().items)
            val growthMb = (peak - baseline) / MB
            val numbers = "baseline ${baseline / KB} KB, peak ${peak / KB} KB, growth ${(peak - baseline) / KB} KB"
            println("HEAP on the device: $numbers; pages alive at most: $mostPagesAlive")
            assertTrue("heap grew by $growthMb MB, the limit is $LIMIT_MB", growthMb < LIMIT_MB)
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
