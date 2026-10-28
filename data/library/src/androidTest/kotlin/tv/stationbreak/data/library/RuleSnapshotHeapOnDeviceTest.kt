// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.util.Log
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
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.testing.SyntheticLibrary
import tv.stationbreak.core.testing.SyntheticRuleInputs
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Clock

/**
 * Spec 10 task 3.5.2 (Goal 4): `ruleSnapshot` on the generated library of 15,000 items with three superseded versions
 * each, 500 series with three versions each and 100 collections of about 50 members (one of 2,000, a few members
 * swapped in every round), on the API 25 emulator, over the platform SQLite that Fire OS 6 has. It must finish in under
 * 3 s, and the result must grow the heap by less than 64 MB, the budget `FirstSyncHeapOnDeviceTest` uses (spec 06). The
 * same shape runs on the JVM (`RuleSnapshotHeapTest`). Generated placeholder data only.
 *
 * It reads four times, the first of them cold, and every read must be under the limit. It samples the raw heap from
 * a plain thread while it reads, as `ScheduleItemsHeapOnDeviceTest` does. It must not call the DAO with a second,
 * different callback: that made API 25's JIT deoptimise the read loop,
 * and the next GC aborted the runtime (see `ItemHistoryDao.heldAsOf`). `ruleSnapshot` passes the test seam only
 * through one call site, and the test sets none. The distinct `String` instances of the genres and tags are counted
 * and must equal the distinct values.
 */
@RunWith(AndroidJUnit4::class)
class RuleSnapshotHeapOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("heap-test-server"), UserId("heap-test-user"))
    private val movies = ItemId("11111111111111111111111111111111")
    private val library = SyntheticLibrary(size = ITEMS, libraryId = movies)
    private val inputs = SyntheticRuleInputs(library, seriesCount = SERIES, collectionCount = COLLECTIONS)
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

    /** Writes the items, the series and the collections once, as [round]: each round supersedes the one before. */
    private suspend fun writeRound(round: Int) {
        var start = 0
        while (start < ITEMS) {
            val page =
                library.page(start, PAGE).map { it.copy(officialRating = "R$round", studios = studioOf(it.id.value)) }
            repository.upsertItems(account, movies, page)
            start += PAGE
        }
        val series = inputs.series(round).map { it.copy(studios = studioOf(it.id.value)) }
        for (chunk in series.chunked(SERIES_PAGE)) repository.upsertSeries(account, movies, chunk)
        for (j in 0 until COLLECTIONS) {
            repository.writeCollection(account, inputs.collectionId(j), "Collection $j", inputs.members(j, round))
        }
        Log.i(TAG, "write round $round done")
    }

    private fun RuleCollection.memberCount() = movies.size + episodes.size + series.size + seasons.size

    private fun sharedStrings(strings: List<String>): Boolean {
        val instances = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
        for (value in strings) instances.add(value)
        return strings.isNotEmpty() && instances.size == strings.toSet().size
    }

    @Test
    fun theRuleSnapshotOfAFullLibraryReadsInUnderThreeSecondsWithLessThan64MbOfHeapGrowth() =
        runBlocking {
            repository.useAccount(account)
            repository.select(account, setOf(movies))
            writeRound(0)
            writeRound(1)
            delay(SETTLE_MS)
            // A cut-off after the second round: most rows are answered by case (b), a version, as on the JVM.
            val cutoff = Clock.System.now()
            delay(SETTLE_MS)
            writeRound(2)
            writeRound(3)
            assertEquals(ITEMS * 3, repository.rowCounts().versions)
            assertEquals(SERIES * 3, repository.rowCounts().seriesVersions)
            val expectedItems = repository.scheduleItems(account, setOf(movies), cutoff)
            val baseline = usedHeap()

            val sampler = HeapSampler(baseline, ::rawUsedHeap).also { it.start() }
            val times = mutableListOf<Long>()
            // The first read is the cold one, which the daily read on a Fire TV is: it is timed and limited too.
            val firstStarted = System.nanoTime()
            var snapshot: RuleSnapshot = repository.ruleSnapshot(account, setOf(movies), cutoff)
            val firstMs = (System.nanoTime() - firstStarted) / NANOS_PER_MS
            repeat(READS) {
                val started = System.nanoTime()
                snapshot = repository.ruleSnapshot(account, setOf(movies), cutoff)
                times += (System.nanoTime() - started) / NANOS_PER_MS
            }
            val peak = sampler.finish()
            Log.i(TAG, "reads done $times")
            val retained = usedHeap() - baseline

            val streamed = peak - baseline
            val tookMs = times.sorted()[times.size / 2]
            val slowestMs = maxOf(firstMs, times.max())
            val members = snapshot.collections.values.sumOf { it.memberCount() }
            val grew = "kept result grew the heap by ${retained / KB} KB"
            println(
                "RULE SNAPSHOT on the device: first (cold) read $firstMs ms, " +
                    "warm reads $times ms, median $tookMs ms; ${snapshot.items.size} items, " +
                    "${snapshot.series.size} series, ${snapshot.collections.size} collections with $members members; " +
                    "$grew; raw peak while reading grew it by ${streamed / KB} KB",
            )
            assertEquals(expectedItems, snapshot.items.map { it.item })
            val known = inputs.series(0).map { CanonicalId.parse(it.id.value) }.toSet()
            val pointedTo = snapshot.items.mapNotNull { it.item.seriesId }.toSet()
            assertEquals(pointedTo.intersect(known), snapshot.series.keys)
            assertEquals(COLLECTIONS, snapshot.collections.size)
            val genres = snapshot.items.flatMap { it.genres } + snapshot.series.values.flatMap { it.genres }
            val tags = snapshot.items.flatMap { it.tags } + snapshot.series.values.flatMap { it.tags }
            assertTrue("genres are not shared", sharedStrings(genres))
            assertTrue("tags are not shared", sharedStrings(tags))
            val took = "a read took $slowestMs ms (first $firstMs, then $times)"
            assertTrue("$took, the limit is $LIMIT_MS", slowestMs < LIMIT_MS)
            assertTrue("the snapshot holds no series", snapshot.series.isNotEmpty())
            assertTrue("the result grew the heap by ${retained / MB} MB, limit $LIMIT_MB", retained / MB < LIMIT_MB)
            assertTrue("reading grew the heap by ${streamed / MB} MB, limit $LIMIT_MB", streamed / MB < LIMIT_MB)
        }

    private companion object {
        const val TAG = "RSH"
        const val ITEMS = 15_000
        const val SERIES = 500
        const val SERIES_PAGE = 100
        const val COLLECTIONS = 100
        const val PAGE = 400
        const val READS = 3
        const val SAMPLE_MS = 5L
        const val SETTLE_MS = 5L
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val LIMIT_MS = 3_000L
        const val NANOS_PER_MS = 1_000_000L
        val STUDIOS = listOf("Studio North", "Studio South", "Studio East", "Studio West", "Studio Centre")

        // One invented studio per item or series (spec 18), the same for an ID every round.
        fun studioOf(id: String) = listOf(STUDIOS[Math.floorMod(id.hashCode(), STUDIOS.size)])
    }
}
