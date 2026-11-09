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
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.testing.SyntheticLibrary
import java.lang.management.ManagementFactory
import java.util.Collections
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
 */
internal class SelectionRepairBenchmark {
    private val minute = 60_000L
    private val atMs = NOON.toEpochMilliseconds() + 10 * minute

    // Library ids, and the digit each library's item and series ids start with (so ids never collide).
    private val big = Lib(ItemId("d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1"), '1', 20_000, 11)
    private val mid = Lib(ItemId("d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2"), '2', 15_000, 12)
    private val small = Lib(ItemId("d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3"), '3', 5_000, 13)
    private val niche = Lib(ItemId("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4"), '4', 2_000, 14, onlyGenre = "Documentary")
    private val all = listOf(big, mid, small, niche)
    private val allIds = all.mapTo(LinkedHashSet()) { it.id }

    private class Lib(
        val id: ItemId,
        val digit: Char,
        val size: Int,
        val seed: Long,
        val onlyGenre: String? = null,
    )

    // One library, seeded once for every scenario: nothing in a selection change writes to it.
    private val library: CountingLibrary by lazy { seeded() }

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
        val runs = ArrayList<Run>()
        repeat(WARM_UPS + MEASURED) { index ->
            val run = runBlocking { body(Tv()) }
            if (index >= WARM_UPS) runs += checkNotNull(run)
        }
        val line = summary(name, runs)
        println("BENCH $line")
        return line
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
        val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
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
            clock.timed(RepairPhase.Warm) { repairer.warmDays(ACCOUNT, checkNotNull(before), atMs) }
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

    private fun gcNow(): Pair<Long, Long> {
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        return beans.sumOf { maxOf(it.collectionCount, 0L) } to beans.sumOf { maxOf(it.collectionTime, 0L) }
    }

    private fun seeded(): CountingLibrary {
        val library = CountingLibrary()
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        for (lib in all) seed(library, lib)
        return library
    }

    private fun seed(
        library: CountingLibrary,
        lib: Lib,
    ) {
        val synthetic = SyntheticLibrary(lib.size, seed = lib.seed, libraryId = lib.id)
        val media =
            synthetic.items
                .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
                .filter { (it.runtime?.inWholeMilliseconds ?: 0L) > 0L }
                .toList()
        val libraryId = CanonicalId.parse(lib.id.value)
        library.seed(media.map { ruleItemOf(it, lib, libraryId) })
        val bySeries = media.filter { it.seriesId != null }.groupBy { idOf(checkNotNull(it.seriesId), lib) }
        library.delegate.seedSeries(
            ACCOUNT,
            *bySeries.map { (id, its) -> seriesOf(id, its.first(), lib) }.toTypedArray(),
        )
        // Every item has a current row, so it is live.
        val rows = media.map { MediaItem(ItemId(idOf(it.id, lib).value), "Synthetic", ItemKind.Movie, null, null) }
        library.delegate.seed(ACCOUNT, lib.id, *rows.toTypedArray())
    }

    // A synthetic id with the library's digit first: the libraries' own ids all start the same way.
    private fun idOf(
        id: ItemId,
        lib: Lib,
    ): CanonicalId = CanonicalId.parse(lib.digit + id.value.drop(1))

    private fun genresOf(
        media: MediaItem,
        lib: Lib,
    ): List<String> = lib.onlyGenre?.let { listOf(it) } ?: media.genres

    private fun ruleItemOf(
        media: MediaItem,
        lib: Lib,
        libraryId: CanonicalId,
    ): RuleItem {
        val episode = media.kind == ItemKind.Episode
        return RuleItem(
            item =
                ScheduleItem(
                    id = idOf(media.id, lib),
                    kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                    runtimeMs = media.runtime?.inWholeMilliseconds ?: 0L,
                    createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = media.productionYear,
                    seriesId = media.seriesId?.let { idOf(it, lib) },
                    seasonNumber = media.seasonNumber,
                    episodeNumber = media.episodeNumber,
                ),
            libraryId = libraryId,
            seasonId = null,
            officialRating = media.officialRating,
            genres = genresOf(media, lib),
            tags = media.tags,
        )
    }

    // A series takes its first episode's rating, genres and tags.
    private fun seriesOf(
        id: CanonicalId,
        first: MediaItem,
        lib: Lib,
    ) = RuleSeries(id, first.officialRating, genresOf(first, lib), first.tags)

    private companion object {
        const val WARM_UPS = 3
        const val MEASURED = 5
        const val STARTERS_ON = 40
        const val CUSTOM_CHANNELS = 10

        // The synthetic library's genres (SyntheticLibrary.GENRES).
        val SyntheticGenres = listOf("Action", "Comedy", "Drama", "Documentary", "Horror", "Music", "Sci-Fi")
    }
}
