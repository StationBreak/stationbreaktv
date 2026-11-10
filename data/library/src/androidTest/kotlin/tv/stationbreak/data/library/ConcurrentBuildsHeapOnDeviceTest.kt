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
import tv.stationbreak.core.domain.lineup.ConcurrentBuildPair
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Clock

/**
 * #684 PR 3, the merge gate of crew:architecture M2, on the API 25 emulator over the platform SQLite that Fire OS 6
 * has: the plan's two builds (what each channel admits of the new and of the current selection) run at the same time,
 * so their transient state is on the heap at once, beside both snapshots (one walk read the two selections). This
 * pairs the two builds as the lanes do on the generated library of two libraries of 7,500 items each (15,000 in all,
 * the size `RuleSnapshotHeapOnDeviceTest` reads), the new selection holding one of them, the current both, over a
 * lineup of the starter catalog with 14 channels on. The heap must grow by less than 64 MB, the budget of
 * `RuleSnapshotHeapOnDeviceTest` and `FirstSyncHeapOnDeviceTest`, both at the raw peak (sampled from a plain thread
 * while the builds run, garbage included) and once what the builds leave behind is collected. The times are logged
 * only: the TV's own timing line is the measure of speed. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentBuildsHeapOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId(SERVER_HEX), UserId(USER_HEX))
    private val first = ItemId("11111111111111111111111111111111")
    private val second = ItemId("22222222222222222222222222222222")
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
    fun twoBuildsAtOnceBesideBothSnapshots_growTheHeapByLessThan64Mb() =
        runBlocking {
            repository.useAccount(account)
            repository.applySelection(account, setOf(first, second), nowMs = 0)
            write(first, itemsOf(first, 'a'))
            write(second, itemsOf(second, 'b'))
            // A full collection pass has run, so the lineup is planned over the library at once.
            repository.recordCollectionPass(account, Clock.System.now(), relisted = true)
            val pair = ConcurrentBuildPair(repository, account)
            pair.prepare(setOf(first, second))
            Log.i(TAG, "lineup: ${pair.channelsOn} on of ${pair.channels}")
            val at = Clock.System.now()
            val baseline = usedHeap()

            val sampler = HeapSampler(baseline, ::rawUsedHeap).also { it.start() }
            val started = System.nanoTime()
            val made = pair.buildBoth(after = setOf(first), before = setOf(first, second), at = at)
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
            val peak = sampler.finish()
            val retained = usedHeap() - baseline

            val streamed = peak - baseline
            Log.i(TAG, "read and two builds at once: $tookMs ms, $made channel days")
            println(
                "TWO BUILDS on the device: $tookMs ms for the walk and both builds, $made channel days; " +
                    "raw peak grew the heap by ${streamed / KB} KB; kept result grew it by ${retained / KB} KB",
            )
            assertTrue("the builds made no channel day", made > 0)
            assertTrue("the builds grew the heap by ${retained / MB} MB, limit $LIMIT_MB", retained / MB < LIMIT_MB)
            val peaked = "the builds' raw peak grew the heap by ${streamed / MB} MB, limit $LIMIT_MB"
            assertTrue(peaked, streamed / MB < LIMIT_MB)
        }

    private companion object {
        const val TAG = "CBH"

        // Canonical ids: the lineup belongs to an account of two of them.
        const val SERVER_HEX = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"
        const val USER_HEX = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
        const val ITEMS_EACH = 7_500
        const val PAGE = 400
        const val SAMPLE_MS = 5L
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val NANOS_PER_MS = 1_000_000L
    }
}
