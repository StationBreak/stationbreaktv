// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * How big the file of a realistic day is, and what reading it costs beside building the day: about 50 channels over
 * 20,000 items (as `DayBuildEquivalenceTest`'s timing harness). It prints its numbers and asserts only generous bounds:
 * the file stays well under the store's cap, and reading it allocates less than working the pools out does (the
 * device's cold build also reads the library snapshot, which allocates far more and is not counted here).
 */
class PersistedDaysSizeTest {
    private val lineupDays = LineupDays(TestCatalog)
    private val genres = listOf("Comedy", "Crime", "Western", "Horror", "Science Fiction", "Animation", "Kids", "Drama")
    private val ratings = listOf("G", "PG", "PG-13", "R", "TV-MA", null, "")

    private fun library(count: Int): List<RuleItem> =
        (1..count).map { n ->
            movie(
                n,
                genres[n % genres.size],
                genres[(n / 7) % genres.size],
                rating = ratings[(n / 3) % ratings.size],
                runtimeMs = RUNTIMES[n % RUNTIMES.size],
            )
        }

    private fun custom(n: Int) =
        channel(
            "custom-$n",
            400 + n,
            ChannelState.On,
            ChannelSource.Custom(rules("Custom $n", Source.Genre(genres[n % genres.size]))),
        )

    private val lineup =
        lineupOf(
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("westerns", 220, ChannelState.On, rules("Westerns", Source.Genre("Western"))),
            starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Horror"))),
            starter("rockets", 320, ChannelState.On, rules("Rockets", Source.Genre("Science Fiction"))),
            *(0 until CUSTOMS).map(::custom).toTypedArray(),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
        )

    private fun allocated(): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return threads.currentThreadAllocatedBytes
    }

    // The fastest of a few runs, and the least it allocated.
    private fun best(run: () -> Unit): Pair<Long, Long> {
        var ms = Long.MAX_VALUE
        var bytes = Long.MAX_VALUE
        repeat(RUNS) {
            val before = allocated()
            val mark = TimeSource.Monotonic.markNow()
            run()
            ms = minOf(ms, mark.elapsedNow().inWholeMilliseconds)
            bytes = minOf(bytes, allocated() - before)
        }
        return ms to bytes
    }

    @Test
    fun theFileOfAFiftyChannelDayIsSmallAndCheapToRead() {
        val items = library(ITEMS)
        val scheduled = lineupDays.scheduled(lineup, NOON.toEpochMilliseconds())
        assertTrue(scheduled.size >= FIFTY, "about 50 channels: ${scheduled.size}")
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), items, emptyMap(), emptyMap(), collectionsSynced = true)
            }
        // Warm the JIT on both paths, then measure one of each.
        repeat(2) { lineupDays.days(lineup, scheduled, snapshots) }
        val store = MemoryDayStore()
        val persisted = PersistedDays(store, "1:1")
        val key = persisted.keyOf(ACCOUNT, LIBRARIES, 1, null, lineup, TestCatalog.version, scheduled)
        val built = lineupDays.days(lineup, scheduled, snapshots)
        runBlocking { persisted.save(ACCOUNT, key, built) }
        repeat(2) { runBlocking { persisted.load(ACCOUNT, key, scheduled, lineupDays::restore) } }

        val (buildMs, buildBytes) = best { lineupDays.days(lineup, scheduled, snapshots) }
        val (readMs, readBytes) = best { runBlocking { persisted.load(ACCOUNT, key, scheduled, lineupDays::restore) } }
        val read = runBlocking { persisted.load(ACCOUNT, key, scheduled, lineupDays::restore) }
        val (encodeMs, encodeBytes) = best { DaySnapshotCodec.encode(key, built) }

        val fileBytes = store.only().second.size
        val poolEntries = built.sumOf { it.pool.size }
        val distinct = built.flatMapTo(HashSet()) { it.pool }.size
        println(
            "PersistedDaysSize channels=${scheduled.size} items=$ITEMS poolEntries=$poolEntries " +
                "distinctItems=$distinct " +
                "fileKb=${fileBytes / KB} buildMs=$buildMs buildAllocKb=${buildBytes / KB} readMs=$readMs " +
                "readAllocKb=${readBytes / KB} encodeMs=$encodeMs encodeAllocKb=${encodeBytes / KB}",
        )
        assertEquals(describe(built), describe(checkNotNull(read)))
        assertTrue(fileBytes < CAP_BYTES, "the file is $fileBytes bytes")
        assertTrue(readBytes < buildBytes, "reading allocated $readBytes bytes, building $buildBytes")
    }

    private companion object {
        const val ITEMS = 20_000
        const val CUSTOMS = 42
        const val FIFTY = 48
        const val KB = 1024
        const val RUNS = 5
        const val CAP_BYTES = 16 * 1024 * 1024
        val RUNTIMES = longArrayOf(5_400_000L, 7_200_000L, 3_600_000L, 1_800_000L, 10_800_000L, 60_000L, 9_000_000L)
    }
}
