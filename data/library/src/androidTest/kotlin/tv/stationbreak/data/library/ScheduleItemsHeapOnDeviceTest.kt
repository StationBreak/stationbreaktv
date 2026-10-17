// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Clock

/**
 * Spec 09 task 2.13 (Goal 4): the as-of read of the generated 15,000-item library with three superseded versions per
 * item (45,000 `item_version` rows), on the API 25 emulator, over the platform SQLite that Fire OS 6 has. The heap
 * budget is the one `FirstSyncHeapOnDeviceTest` uses (spec 06): less than 64 MB of growth. The same test runs on the
 * JVM (`ScheduleItemsHeapTest`), which also holds the one-second limit; the device time is printed, not asserted,
 * because an emulator's speed says nothing about a Fire TV's. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleItemsHeapOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("heap-test-server"), UserId("heap-test-user"))
    private val movies = ItemId("11111111111111111111111111111111")
    private val library = SyntheticLibrary(size = ITEMS, libraryId = movies)
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

    private fun usedHeap(): Long {
        repeat(GCS) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Writes the whole library once, with the rating of [round], so each round supersedes the one before. */
    private suspend fun writeRound(round: Int) {
        var start = 0
        while (start < ITEMS) {
            val page = library.page(start, PAGE).map { it.copy(officialRating = "R$round") }
            repository.upsertItems(account, movies, page)
            start += PAGE
        }
    }

    @Test
    fun fifteenThousandItemsWithThreeVersionsEachReadWithLessThan64MbOfHeapGrowth() =
        runBlocking {
            repository.useAccount(account)
            repository.select(account, setOf(movies))
            writeRound(0)
            writeRound(1)
            delay(SETTLE_MS)
            // A cut-off after the second round: most items are answered by case (b), a version, as on the JVM.
            val cutoff = Clock.System.now()
            delay(SETTLE_MS)
            writeRound(2)
            writeRound(3)
            assertEquals(ITEMS * 3, repository.rowCounts().versions)
            val usable =
                library.items.count {
                    it.kind in setOf(ItemKind.Movie, ItemKind.Episode) && (it.runtime?.inWholeMilliseconds ?: 0) > 0
                }
            val baseline = usedHeap()

            val started = System.nanoTime()
            val read = repository.scheduleInputs(account, setOf(movies), cutoff)
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
            val retained = usedHeap() - baseline

            var peak = baseline
            var chunks = 0
            repository.dao.heldAsOf(account.server.value, account.user.value, cutoff.toEpochMilliseconds(), CHUNK) {
                chunks++
                if (chunks % SAMPLE_EVERY == 0) peak = maxOf(peak, usedHeap())
            }

            val streamed = peak - baseline
            println(
                "SCHEDULE READ on the device: $tookMs ms; ${read.items.size} items of " +
                    "${repository.rowCounts().items}; kept result grew the heap by ${retained / KB} KB; " +
                    "streaming peak grew it by ${streamed / KB} KB over $chunks chunks",
            )
            assertEquals(usable, read.items.size)
            assertTrue("the result grew the heap by ${retained / MB} MB, limit $LIMIT_MB", retained / MB < LIMIT_MB)
            assertTrue("streaming grew the heap by ${streamed / MB} MB, limit $LIMIT_MB", streamed / MB < LIMIT_MB)
        }

    private companion object {
        const val ITEMS = 15_000
        const val PAGE = 400
        const val CHUNK = 500
        const val SAMPLE_EVERY = 8
        const val SETTLE_MS = 5L
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val NANOS_PER_MS = 1_000_000L
    }
}
