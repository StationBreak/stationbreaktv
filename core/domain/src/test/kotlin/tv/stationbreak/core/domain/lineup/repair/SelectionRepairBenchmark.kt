// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.DayBuild
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.selectionOf
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.Source
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A benchmark, not a test (spec 26: the slow untick on older hardware): a selection change's repair over a synthetic
 * library at real scale, three libraries of about 20,000, 15,000 and 5,000 items plus a small one-genre library, the
 * whole starter catalog planned over them. Each scenario runs on a fresh lineup, source and repairer, three times to
 * warm up and five times measured, and prints the median of each step ([RepairPhases]) with the library reads. It
 * runs only with `STATIONBREAK_BENCH=1`, so a plain test run skips it. Synthetic data only; no server.
 *
 * Each scenario runs in two modes. `plain` reads the in-memory library as fast as it answers. `room-like` makes the
 * reads take turns as on the device: one fair lock stands for the single Room writer connection, a snapshot read holds
 * it about 330 ms and a liveness read about 8 microseconds an id (the numbers the timing line gave on the slow TV).
 * Each run ends with the SHA-256 of the stored lineup file, and the summary says whether every run of both modes stored
 * the same bytes (`sameBytes`): a faster repair that stores other bytes is not the same repair.
 *
 * It is not part of `check`: it is excluded from the module's `test` task and run on its own, from the repository root:
 *
 *     ./gradlew :core:domain:selectionRepairBenchmark
 *
 * (with the toolchain prefix in the builder checks; the task sets `STATIONBREAK_BENCH=1` and prints the output). Only
 * the bytes are compared by a test (`SelectionRepairEqualityTest`); the times gate nothing.
 */
internal class SelectionRepairBenchmark {
    private val minute = 60_000L
    private val atMs = NOON.toEpochMilliseconds() + 10 * minute

    // Library ids, and the digit each library's item and series ids start with (so ids never collide).
    private val big = SyntheticLib(ItemId("d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1"), '1', 20_000, 11)
    private val mid = SyntheticLib(ItemId("d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2"), '2', 15_000, 12)
    private val small = SyntheticLib(ItemId("d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3"), '3', 5_000, 13)
    private val niche =
        SyntheticLib(ItemId("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4"), '4', 2_000, 14, onlyGenre = "Documentary")
    private val all = listOf(big, mid, small, niche)
    private val allIds = all.mapTo(LinkedHashSet()) { it.id }

    // One library, seeded once for every scenario: nothing in a selection change writes to it.
    private val library: CountingLibrary by lazy { seededLibrary(all) }

    @Test
    fun selectionChangeRepair() {
        assumeTrue(System.getenv("STATIONBREAK_BENCH") == "1", "set STATIONBREAK_BENCH=1 to run the benchmark")
        val results = ArrayList<String>()
        results +=
            scenario("S1 untick 5k, days laid out") { tv ->
                tv.warm()
                tv.untick(setOf(small.id))
            }
        results +=
            scenario("S2 untick 5k, days not laid out") { tv ->
                // Counted without reading the layouts, so the repair is the first to lay the days out.
                tv.untick(setOf(small.id), counted = tv.built().size to -1)
            }
        results +=
            scenario("S3 second untick (15k) on repaired days") { tv ->
                tv.warm()
                tv.untick(setOf(small.id), measured = false)
                tv.warm()
                tv.untick(setOf(mid.id))
            }
        results +=
            scenario("S4 untick 5k right after an add, days not built") { tv ->
                tv.chosen = allIds - niche.id
                val counted = tv.warm().size to tv.touchedBy(setOf(small.id))
                tv.add(niche.id)
                tv.untick(setOf(small.id), counted = counted)
            }
        results +=
            scenario("S5 untick small one-genre library") { tv ->
                tv.warm()
                tv.untick(setOf(niche.id))
            }
        results +=
            scenario("S6 untick 20k (feeds most channels)") { tv ->
                tv.warm()
                tv.untick(setOf(big.id))
            }
        println("BENCH ==== summary (median of $MEASURED, ms) ====")
        results.forEach { println("BENCH $it") }
    }

    // One measured run of a scenario.
    @Suppress("LongParameterList") // What one run reports, one for one.
    private class Run(
        val phases: RepairPhases,
        val wallMs: Long,
        val gcCount: Long,
        val gcMs: Long,
        val reads: Reads,
        val channels: Int,
        val touched: Int,
        val emptied: Int,
        val buildMs: Long,
        val buildPoolsMs: Long,
        val digest: String,
    )

    private class Reads(
        val snapshots: Int,
        val currentIds: Int,
        val currentIdIds: Int,
        val itemsOf: Int,
    )

    private fun scenario(
        name: String,
        body: suspend (Tv) -> Run?,
    ): String {
        val lines = ArrayList<String>()
        var plainDigests: Set<String>? = null
        for (mode in Mode.entries) {
            mode.install(library)
            val runs = ArrayList<Run>()
            try {
                repeat(WARM_UPS + MEASURED) { index ->
                    val run = runBlocking { body(Tv()) }
                    if (index >= WARM_UPS) runs += checkNotNull(run)
                }
            } finally {
                Mode.Plain.install(library)
            }
            val digests = runs.mapTo(HashSet()) { it.digest }
            val first = plainDigests ?: digests.also { plainDigests = it }
            val same = digests.size == 1 && digests == first
            val shown = digests.first().take(DIGEST_SHOWN)
            val line = "[${mode.label}] ${summary(name, runs)} | sameBytes=$same digest=$shown"
            println("BENCH $line")
            lines += line
        }
        return lines.joinToString("\nBENCH ")
    }

    /** How the library answers: at once, or with the reads taking turns as Room's one writer connection makes them. */
    private enum class Mode(
        val label: String,
    ) {
        Plain("plain"),
        RoomLike("room-like"),
        ;

        fun install(library: CountingLibrary) {
            if (this == Plain) {
                library.afterSnapshot = {}
                library.currentIdsLatency = {}
                return
            }
            val room = ReentrantLock(true)

            fun hold(ms: Long) {
                room.lock()
                try {
                    Thread.sleep(ms)
                } finally {
                    room.unlock()
                }
            }
            library.afterSnapshot = { hold(SNAPSHOT_MS) }
            library.currentIdsLatency = { ids -> hold(ids * ID_MICROS / MICROS_PER_MS) }
        }
    }

    private fun summary(
        name: String,
        runs: List<Run>,
    ): String {
        fun med(of: (Run) -> Long): Long = runs.map(of).sorted()[runs.size / 2]
        val p = { of: (RepairPhases) -> Long -> med { of(it.phases) } }
        val first = runs.first()
        return "$name: wall=${med { it.wallMs }} warm=${p { it.warmMs }} days=${p { it.daysMs }} " +
            "snaps=${p { it.snapsMs }} " +
            "admitted=${p { it.admittedMs }} before=${p { it.beforeMs }} kept=${p { it.keptMs }} " +
            "pools=${p { it.poolsMs }} live=${p { it.liveMs }} layout=${p { it.layoutMs }} pin=${p { it.pinMs }} " +
            "write=${p {
                it.writeMs
            }} other=${p { it.otherMs }} cpu=${p { it.cpuMs }} gc=${med { it.gcCount }} gcMs=${med { it.gcMs }} | " +
            "channels=${first.channels} touched=${first.touched} " +
            "emptied=${first.emptied} snapshotReads=${first.reads.snapshots} " +
            "currentIdsCalls=${first.reads.currentIds} currentIdsIds=${first.reads.currentIdIds} " +
            "itemsOfCalls=${first.reads.itemsOf} | fullBuild=${med { it.buildMs }} buildPools=${med { it.buildPoolsMs }}"
    }

    /** One TV over [library]: a lineup planned over every library, a source and a repairer, all fresh. */
    private inner class Tv {
        var chosen: Set<ItemId> = allIds
        var applied = 0L
        private val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
        private val clock =
            object : Clock {
                override fun now(): Instant = NOON
            }
        val store = RecordingStore()
        val service = LineupService(store, library, clock, { 0 }, {}, StarterCatalog)
        val source =
            BuiltInScheduleSource(service, library, built = { builds += it }) { selectionOf(chosen, applied) }
        val repairer = TodayRepairer(service, source, library) { applied }

        init {
            runBlocking {
                service.refresh(ACCOUNT, allIds)
                // More starters on (in catalog order) up to [STARTERS_ON] on, then [CUSTOM_CHANNELS] custom channels:
                // about 50 channels on, a full lineup at real scale.
                val planned = checkNotNull(service.loaded(ACCOUNT)).lineup
                var on = planned.channels.count { it.state == ChannelState.On }
                for (channel in planned.channels) {
                    if (on >= STARTERS_ON) break
                    if (channel.state != ChannelState.On && channel.source is ChannelSource.Starter) {
                        service.edit(ACCOUNT, LineupEdit.SwitchOn(channel.id, confirmedOptIn = true), allIds)
                        on++
                    }
                }
                repeat(CUSTOM_CHANNELS) { n ->
                    val genre = SyntheticGenres[n % SyntheticGenres.size]
                    val sources =
                        if (n < SyntheticGenres.size) {
                            listOf(Source.Genre(genre))
                        } else {
                            listOf(Source.Genre(genre), Source.Library(CanonicalId.parse(all[n % all.size].id.value)))
                        }
                    val custom = rules("Custom $n", *sources.toTypedArray())
                    service.edit(ACCOUNT, LineupEdit.AddCustom(ChannelId("custom:c$n"), custom, 700 + n, false), allIds)
                }
            }
        }

        // Today's days under the saved selection, built (pools, no layouts).
        suspend fun days(): List<ChannelDay> {
            val saved = SavedSelection(chosen, applied, chosen, 0L)
            return source.channelDays(ACCOUNT, saved, Instant.fromEpochMilliseconds(atMs))
        }

        suspend fun built(): List<ChannelDay> = days()

        // Built and every day laid out, as after the guide has shown them all.
        suspend fun warm(): List<ChannelDay> = days().onEach { it.day }

        suspend fun add(lib: ItemId) {
            val outcome =
                repairer.saveWithoutRepair(ACCOUNT, 0L) { seq ->
                    chosen = chosen + lib
                    applied = seq
                    true
                }
            assertIs<RepairOutcome.Saved<Long>>(outcome)
        }

        // The last full day build's time (its pools part), for the calibration against the TV's DayBuild line.
        fun lastBuild(): DayBuild? = builds.lastOrNull()

        /**
         * Unticks [removed], saving the change; the run's numbers when [measured]. [counted] is how many channel days
         * there are and how many air [removed] after the change (worked out first when not given: that reads the
         * current days and lays them out, so a scenario that must find them unbuilt or not laid out gives them; -1 is
         * not counted).
         */
        suspend fun untick(
            removed: Set<ItemId>,
            measured: Boolean = true,
            counted: Pair<Int, Int>? = null,
        ): Run? {
            val build = lastBuild()
            val before = source.savedSelection()
            val (channels, touched) = counted ?: if (measured) (days().size to touchedBy(removed)) else (-1 to -1)
            val reads = Counter(library)
            val gc = gcNow()
            val clock = RepairClock()
            val began = TimeSource.Monotonic.markNow()
            val after = chosen - removed
            // As the selection's allocation does: today's days warmed before the turn, with the saved selection.
            clock.timed(RepairPhase.Warm) { repairer.warmDays(ACCOUNT, atMs) }
            val outcome =
                repairer.selectionChanged(ACCOUNT, after, atMs, 0L, clock = clock) { seq ->
                    chosen = after
                    applied = seq
                    true
                }
            val wallMs = began.elapsedNow().inWholeMilliseconds
            val gcAfter = gcNow()
            assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome, "the change before was $before")
            // The repairs whose tail has nothing to air: each needs the new selection's build for its reason.
            val seq = outcome.result.seq
            val emptied =
                checkNotNull(service.loaded(ACCOUNT)).repairs.selection.count {
                    it.seq == seq && it.tailHidden != null
                }
            if (!measured) return null
            val digest = digestOf(checkNotNull(store.inner.peek(ACCOUNT)) { "the lineup file is stored" })
            return Run(
                clock.phases(),
                wallMs,
                gcAfter.first - gc.first,
                gcAfter.second - gc.second,
                reads.done(),
                channels,
                touched,
                emptied,
                build?.ms ?: -1L,
                build?.poolsMs ?: -1L,
                digest,
            )
        }

        // How many channel days air something of [removed] from the half hour before the change to the day's end.
        suspend fun touchedBy(removed: Set<ItemId>): Int {
            val digits = all.filter { it.id in removed }.mapTo(HashSet()) { it.digit }
            val fromMs = atMs - Math.floorMod(atMs, 30 * minute)
            return days().count { day ->
                day.day.blocks.any { block ->
                    val item = (block.content as? ScheduleContent.Program)?.itemId
                    block.endMs > fromMs && item != null && item.value.first() in digits
                }
            }
        }
    }

    // The library's read counts over one change.
    private class Counter(
        private val library: CountingLibrary,
    ) {
        private val snapshots = library.snapshotCutoffs.size
        private val currentIds = library.currentIdsCalls
        private val currentIdIds = library.currentIdsAsked
        private val itemsOf = library.scheduleItemsOfCalls

        fun done() =
            Reads(
                library.snapshotCutoffs.size - snapshots,
                library.currentIdsCalls - currentIds,
                library.currentIdsAsked - currentIdIds,
                library.scheduleItemsOfCalls - itemsOf,
            )
    }

    private fun digestOf(bytes: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(sha.digest(bytes))
    }

    private fun gcNow(): Pair<Long, Long> {
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        return beans.sumOf { maxOf(it.collectionCount, 0L) } to beans.sumOf { maxOf(it.collectionTime, 0L) }
    }

    private companion object {
        const val WARM_UPS = 3
        const val MEASURED = 5
        const val STARTERS_ON = 40
        const val CUSTOM_CHANNELS = 10

        // The room-like reads: what a snapshot read and a liveness id cost on the slow TV, from its timing line.
        const val SNAPSHOT_MS = 330L
        const val ID_MICROS = 8L
        const val MICROS_PER_MS = 1000L
        const val DIGEST_SHOWN = 16

        // The synthetic library's genres (SyntheticLibrary.GENRES).
        val SyntheticGenres = listOf("Action", "Comedy", "Drama", "Documentary", "Horror", "Music", "Sci-Fi")
    }
}
