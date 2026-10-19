// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.testing.SyntheticLibrary
import tv.stationbreak.core.testing.SyntheticRuleInputs
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Spec 10 task 3.5.1 (Goal 4, Granny H1, Vimes L5): `ruleSnapshot` on the generated library of 15,000 items with three
 * superseded versions each (45,000 `item_version` rows), 500 series with three versions each, and 100 collections of
 * about 50 members (one of 2,000) whose members change in every round. It finishes within the JVM guard of
 * [LIMIT_MS] (1 s, the item read's own guard: it measured about 165 ms here, so the guard catches a gross
 * regression and does not flake on a loaded host), and the result grows the heap by less than the spec 06 budget of
 * 64 MB. The same read runs on the API 25 emulator (`RuleSnapshotHeapOnDeviceTest`), which holds the 3 s limit.
 *
 * The repeated strings are shared within the read: the distinct `String` instances of the genres, the tags, the
 * ratings and the library IDs are as many as the distinct values.
 */
class RuleSnapshotHeapTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val library = SyntheticLibrary(size = ITEMS, libraryId = RULE_LIBRARY)
    private val inputs = SyntheticRuleInputs(library, seriesCount = SERIES, collectionCount = COLLECTIONS)

    @After
    fun close() = repository.close()

    private fun usedHeap(): Long {
        repeat(GCS) {
            System.gc()
            System.runFinalization()
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Writes the items, the series and the collections [ROUNDS] times; each round changes them, so it supersedes. */
    private suspend fun fill() {
        repository.select(ALICE, setOf(RULE_LIBRARY))
        repeat(ROUNDS) { round ->
            clock.stepMs(1.hours.inWholeMilliseconds)
            var start = 0
            while (start < ITEMS) {
                val page = library.page(start, PAGE).map { it.copy(officialRating = "R$round") }
                repository.upsertItems(ALICE, RULE_LIBRARY, page)
                start += PAGE
            }
            inputs.series(round).chunked(SERIES_PAGE).forEach { repository.upsertSeries(ALICE, RULE_LIBRARY, it) }
            for (j in 0 until COLLECTIONS) {
                repository.writeCollection(ALICE, inputs.collectionId(j), "Collection $j", inputs.members(j, round))
            }
        }
    }

    private fun RuleCollection.memberCount() = movies.size + episodes.size + series.size + seasons.size

    // How many distinct String instances a collection of strings holds, against how many distinct values.
    private fun instancesAndValues(strings: List<String>): Pair<Int, Int> {
        val instances = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
        strings.forEach { instances.add(it) }
        return instances.size to strings.toSet().size
    }

    private fun assertShared(
        what: String,
        strings: List<String>,
    ) {
        val (instances, values) = instancesAndValues(strings)
        assertTrue("$what: no values to share", values > 0)
        assertEquals("$what: $instances instances for $values values", values, instances)
    }

    private fun assertStringsAreShared(snapshot: RuleSnapshot) {
        val genres = snapshot.items.flatMap { it.genres } + snapshot.series.values.flatMap { it.genres }
        val tags = snapshot.items.flatMap { it.tags } + snapshot.series.values.flatMap { it.tags }
        assertShared("genres", genres)
        assertShared("tags", tags)
        val itemRatings = snapshot.items.mapNotNull { it.officialRating }
        assertShared("ratings", itemRatings + snapshot.series.values.mapNotNull { it.officialRating })
        assertShared("library IDs", snapshot.items.map { it.libraryId.value })
        println("RULE SNAPSHOT strings: ${genres.size} genre and ${tags.size} tag references, all shared")
    }

    @Test
    fun fifteenThousandItemsFiveHundredSeriesAndAHundredCollectionsReadFastWithinTheHeapBudget() =
        runBlocking {
            fill()
            assertEquals(ITEMS * (ROUNDS - 1), repository.rowCounts().versions)
            assertEquals(SERIES * (ROUNDS - 1), repository.rowCounts().seriesVersions)
            // A cut-off between the second and third round: most rows are answered by case (b), a version.
            val cutoff = Instant.fromEpochMilliseconds(EPOCH.toEpochMilliseconds() + 2 * 3_600_000L + 1)
            val expectedItems = repository.scheduleItems(ALICE, setOf(RULE_LIBRARY), cutoff)
            val baseline = usedHeap()

            val times = mutableListOf<Long>()
            var kept: RuleSnapshot? = null
            repeat(READS) {
                val started = System.nanoTime()
                kept = repository.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), cutoff)
                times += (System.nanoTime() - started) / NANOS_PER_MS
            }
            val snapshot = kept!!
            val retained = usedHeap() - baseline

            val median = times.sorted()[times.size / 2]
            val first = times.first()
            val slowest = times.max()
            val members = snapshot.collections.values.sumOf { it.memberCount() }
            println(
                "RULE SNAPSHOT on the JVM: first (cold) read $first ms, " +
                    "reads $times ms, median $median ms; ${snapshot.items.size} items, " +
                    "${snapshot.series.size} series, ${snapshot.collections.size} collections with $members members; " +
                    "kept result grew the heap by ${retained / KB} KB",
            )
            assertEquals(expectedItems, snapshot.items.map { it.item })
            val pointedTo = snapshot.items.mapNotNull { it.item.seriesId }.toSet()
            val known = inputs.series(0).map { CanonicalId.parse(it.id.value) }.toSet()
            assertEquals(pointedTo.intersect(known), snapshot.series.keys)
            // Round 1's series values: the version held before the cut-off.
            val round1 = inputs.series(1).associateBy { CanonicalId.parse(it.id.value) }
            val ratings = snapshot.series.mapValues { it.value.officialRating }
            assertEquals(round1.mapValues { it.value.officialRating }.filterKeys { it in ratings }, ratings)
            assertEquals(COLLECTIONS, snapshot.collections.size)
            assertTrue("the big collection holds $members members in all", members > BIG_COLLECTION)
            assertStringsAreShared(snapshot)
            assertTrue("a read took $slowest ms (all: $times), the limit is $LIMIT_MS", slowest < LIMIT_MS)
            assertTrue("the snapshot holds no series", snapshot.series.isNotEmpty())
            assertTrue("the result grew the heap by ${retained / MB} MB, limit $LIMIT_MB", retained / MB < LIMIT_MB)
        }

    private companion object {
        const val ITEMS = 15_000
        const val SERIES = 500
        const val SERIES_PAGE = 100
        const val COLLECTIONS = 100
        const val BIG_COLLECTION = 1_900
        const val ROUNDS = 4
        const val PAGE = 400
        const val READS = 3
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val LIMIT_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
