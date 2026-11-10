// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.repair.RealRepairRun
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Clock

/**
 * #684 PR 3, the merge gate of crew:architecture M2, on the API 25 emulator over the platform SQLite that Fire OS 6
 * has: the real selection-change plan (`SelectionRepair.plan` in the repairer's turn, with its lanes) over the Room
 * repository, on three libraries of 11,000 generated items (33,000 in all, past the owner's scale) and a lineup of
 * every starter the fixture can switch on. A first untick repairs the days; the second plans on repaired days with a
 * removed library, so one walk reads the new, the current and the removed selections and the two builds, the early
 * current-row read, the tail pools and the kept windows are all live at the peak. The heap must grow by less than
 * 64 MB, the budget of `RuleSnapshotHeapOnDeviceTest` and `FirstSyncHeapOnDeviceTest`, at the raw peak (sampled every
 * 5 ms from a plain thread, garbage included) and for what the change leaves reachable (the repairs recorded, the
 * caches), measured with the run still referenced. Time is logged only: the TV's own timing line is the measure of
 * speed. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentBuildsHeapOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId(SERVER_HEX), UserId(USER_HEX))
    private val first = ItemId("11111111111111111111111111111111")
    private val second = ItemId("22222222222222222222222222222222")
    private val third = ItemId("33333333333333333333333333333333")
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

    private fun rawUsedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Polls the raw heap (no forced GC, garbage included) until stopped; [peak] is the highest figure seen. */
    private class HeapSampler(
        start: Long,
        private val sample: () -> Long,
    ) : Thread("heap-sampler") {
        @Volatile var peak = start

        @Volatile private var running = true

        init {
            isDaemon = true
        }

        override fun run() {
            while (running) {
                peak = maxOf(peak, sample())
                sleep(SAMPLE_MS)
            }
        }

        fun finish(): Long {
            running = false
            join()
            return peak
        }
    }

    // [size] items of [library] under [libraryId], their ids made apart from the other library's.
    private fun itemsOf(
        libraryId: ItemId,
        letter: Char,
    ): List<MediaItem> =
        SyntheticLibrary(size = ITEMS_EACH, libraryId = libraryId)
            .items
            .map { item ->
                item.copy(
                    id = ItemId(letter + item.id.value.drop(1)),
                    seriesId = item.seriesId?.let { ItemId(letter + it.value.drop(1)) },
                )
            }.toList()

    private suspend fun write(
        libraryId: ItemId,
        items: List<MediaItem>,
    ) {
        for (page in items.chunked(PAGE)) {
            repository.upsertItems(account, libraryId, stamp = null, rewalk = false, page)
        }
    }

    @Test
    fun aSecondUntickThroughTheRealPlan_growsTheHeapByLessThan64Mb() =
        runBlocking {
            val libraries = listOf(first, second, third)
            repository.useAccount(account)
            repository.applySelection(account, libraries.toSet(), nowMs = 0)
            write(first, itemsOf(first, 'a'))
            write(second, itemsOf(second, 'b'))
            write(third, itemsOf(third, 'c'))
            // A full collection pass has run, so the lineup is planned over the library at once.
            repository.recordCollectionPass(account, Clock.System.now(), relisted = true)
            val run = RealRepairRun(repository, account)
            run.prepare(libraries.toSet())
            // The first untick repairs the days; the second plans on repaired days with a removed library.
            assertTrue("the first untick was saved", run.untick(setOf(third)))
            val baseline = usedHeap()

            val sampler = HeapSampler(baseline, ::rawUsedHeap).also { it.start() }
            val started = System.nanoTime()
            val saved = run.untick(setOf(second))
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
            val peak = sampler.finish()
            // What the change leaves reachable (the record of the repairs, the caches) is measured, not garbage.
            val retained = usedHeap() - baseline
            val repairs = run.repairs()

            val streamed = peak - baseline
            val items = ITEMS_EACH * libraries.size
            val walks = run.walkSizes.toList()
            Log.i(TAG, "lineup: ${run.channelsOn} on of ${run.channels}; items $items; walks $walks; repairs $repairs")
            println(
                "REAL PLAN on the device: second untick $tookMs ms over $items items, ${run.channelsOn} channels on " +
                    "of ${run.channels}, $repairs channel days repaired; walks $walks; " +
                    "raw peak grew the heap by ${streamed / KB} KB; retained ${retained / KB} KB",
            )
            assertTrue("the second untick was saved", saved)
            assertTrue("the second plan walked the new, the current and the removed", walks.last() == THREE)
            assertTrue("no day was repaired", repairs > 0)
            assertTrue("the plan left ${retained / MB} MB on the heap, limit $LIMIT_MB", retained / MB < LIMIT_MB)
            val peaked = "the plan's raw peak grew the heap by ${streamed / MB} MB, limit $LIMIT_MB"
            assertTrue(peaked, streamed / MB < LIMIT_MB)
        }

    private companion object {
        const val TAG = "CBH"

        // Canonical ids: the lineup belongs to an account of two of them.
        const val SERVER_HEX = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"
        const val USER_HEX = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
        const val ITEMS_EACH = 11_000
        const val THREE = 3
        const val PAGE = 400
        const val SAMPLE_MS = 5L
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val NANOS_PER_MS = 1_000_000L
    }
}
