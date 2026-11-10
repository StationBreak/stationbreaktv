// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.DayBuild
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupSeams
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.RefreshMeta
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.selectionOf
import tv.stationbreak.core.domain.lineup.timing
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/** What a planner is given: everything `TodayRepairer`'s turn gives `SelectionRepair`. */
@Suppress("LongParameterList") // Everything the turn gives `SelectionRepair`, one for one.
internal class PlanInputs(
    val days: LineupDays,
    val library: LibraryRepository,
    val account: LibraryAccount,
    val held: Revisioned,
    val change: SelectionChange,
    val shared: SharedRead,
    val today: List<ChannelDay>,
)

/** Something that plans one change: today's `SelectionRepair`, the frozen reference, or a broken variant. */
internal typealias Planner = suspend (PlanInputs) -> PlannedRepair

/** The planners the equality harness runs. */
internal object Planners {
    /** `SelectionRepair` as main has it now. */
    val production: Planner =
        { SelectionRepair(it.days, it.library, it.account, it.held, it.change, it.shared).plan(it.today) }

    /** The frozen copy ([ReferenceSelectionRepair]). */
    val reference: Planner =
        { ReferenceSelectionRepair(it.days, it.library, it.account, it.held, it.change, it.shared).plan(it.today) }

    /** [production] fed [mutate]d inputs: a deliberately broken variant, injected from outside. */
    fun mutated(mutate: (PlanInputs) -> PlanInputs): Planner = { production(mutate(it)) }
}

/** How a world's lineup is made. */
internal sealed interface LineupShape {
    /**
     * The whole starter catalog planned over every library (a refresh), then more starters switched on up to
     * [startersOn], and [customs] custom channels over genres and libraries, as the benchmark's TV.
     */
    class Catalog(
        val startersOn: Int,
        val customs: Int,
    ) : LineupShape

    /**
     * A hand-made lineup of custom channels (one per genre, one per library, one that no item fits, one with a day
     * that starts at 18:00), stored with the extras filter starting at [extrasFromMs] (null: never).
     */
    class Hand(
        val extrasFromMs: Long?,
    ) : LineupShape
}

/** One change's outcome as the equality compares it: the pins, the whole record, and the digest of the stored bytes. */
internal data class Step(
    val pins: List<Pin>,
    val record: TodayRepairs,
    val digest: String,
)

/**
 * One TV for the equality matrix and the benchmark: a library of [libs], a lineup, a store that keeps the file, a
 * service, a source and a repairer, all fresh. A change runs through the real `TodayRepairer` when [planner] is null,
 * else through [direct]: the same turn written out in the test, with [planner] doing the planning.
 */
internal class RepairWorld(
    val libs: List<SyntheticLib>,
    shape: LineupShape,
    val library: CountingLibrary = seededLibrary(libs),
    private val planner: Planner? = null,
) {
    val atMs: Long = NOON.toEpochMilliseconds() + TEN_MINUTES
    val allIds: Set<ItemId> = libs.mapTo(LinkedHashSet()) { it.id }
    var chosen: Set<ItemId> = allIds
    var applied = 0L
    val store = RecordingStore()
    private val builds = Collections.synchronizedList(mutableListOf<DayBuild>())
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    val service: LineupService
    val source: BuiltInScheduleSource
    val repairer: TodayRepairer
    val steps = ArrayList<Step>()

    init {
        service =
            when (shape) {
                is LineupShape.Catalog -> LineupService(store, library, clock, { 0 }, {}, StarterCatalog)
                is LineupShape.Hand -> LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog))
            }
        source = BuiltInScheduleSource(service, library, built = { builds += it }) { selectionOf(chosen, applied) }
        repairer = TodayRepairer(service, source, library) { applied }
        runBlocking {
            when (shape) {
                is LineupShape.Catalog -> {
                    planCatalog(shape)
                }

                is LineupShape.Hand -> {
                    val meta = RefreshMeta(extrasFromMs = shape.extrasFromMs)
                    store.inner.save(ACCOUNT, LineupCodec.encode(handLineup(), meta, TodayRepairs.NONE))
                }
            }
        }
    }

    private suspend fun planCatalog(shape: LineupShape.Catalog) {
        service.refresh(ACCOUNT, allIds)
        var on = checkNotNull(service.loaded(ACCOUNT)).lineup.channels.count { it.state == ChannelState.On }
        for (channel in checkNotNull(service.loaded(ACCOUNT)).lineup.channels) {
            if (on >= shape.startersOn) break
            if (channel.state != ChannelState.On && channel.source is ChannelSource.Starter) {
                service.edit(ACCOUNT, LineupEdit.SwitchOn(channel.id, confirmedOptIn = true), allIds)
                on++
            }
        }
        repeat(shape.customs) { n ->
            val genre = GENRES[n % GENRES.size]
            val sources =
                if (n < GENRES.size) {
                    listOf(Source.Genre(genre))
                } else {
                    listOf(Source.Genre(genre), Source.Library(CanonicalId.parse(libs[n % libs.size].id.value)))
                }
            val custom = rules("Custom $n", *sources.toTypedArray())
            service.edit(ACCOUNT, LineupEdit.AddCustom(ChannelId("custom:c$n"), custom, 700 + n, false), allIds)
        }
    }

    private fun handLineup(): Lineup {
        val channels = ArrayList<LineupChannel>()
        var number = 100

        fun on(
            id: String,
            vararg sources: Source,
            dayTiming: Timing = timing(),
        ) {
            val source = ChannelSource.Custom(rules(id, *sources, timing = dayTiming))
            channels += channel("custom:$id", number++, ChannelState.On, source)
        }
        for (genre in GENRES) on("genre-${genre.lowercase()}", Source.Genre(genre))
        for (lib in libs) on("lib-${lib.digit}", Source.Library(CanonicalId.parse(lib.id.value)))
        // A channel that no item fits: hidden all day, whatever the selection.
        on("nothing", Source.Genre("Nonexistent Genre"))
        // A mix of a genre and a library, and a day that starts at 18:00: a second cut-off.
        on("mixed", Source.Genre("Documentary"), Source.Library(CanonicalId.parse(libs.last().id.value)))
        on("evening", Source.Genre("Action"), dayTiming = timing(start = "18:00"))
        on("evening-lib", Source.Library(CanonicalId.parse(libs.first().id.value)), dayTiming = timing(start = "18:00"))
        return lineupOf(*channels.toTypedArray())
    }

    /** Today's days under the saved selection, built (pools, no layouts). */
    suspend fun days(): List<ChannelDay> =
        source.channelDays(ACCOUNT, SavedSelection(chosen, applied, chosen, 0L), Instant.fromEpochMilliseconds(atMs))

    /** Built and every day laid out, as after the guide has shown them all. */
    suspend fun warm(): List<ChannelDay> = days().onEach { it.day }

    /** The days' programmes' item ids, in a fixed order. */
    suspend fun airing(): List<CanonicalId> =
        days()
            .flatMap { day -> day.day.blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId } }
            .distinct()
            .sortedBy { it.value }

    /** Deletes (tombstones) every [every]th item that airs today, as a sync that finds them gone does. */
    suspend fun deleteAiring(every: Int) {
        val ids = airing().filterIndexed { index, _ -> index % every == 0 }
        library.delegate.tombstone(ACCOUNT, ids.map { ItemId(it.value) })
    }

    /** Saves an add of [lib] with no repair, as an add of a library does. */
    suspend fun add(lib: ItemId) {
        val outcome =
            repairer.saveWithoutRepair(ACCOUNT, 0L) { seq ->
                chosen = chosen + lib
                applied = seq
                true
            }
        assertIs<RepairOutcome.Saved<Long>>(outcome)
    }

    /** Unticks [removed]. */
    suspend fun untick(removed: Set<ItemId>): Step = change(chosen - removed)

    /** Changes the selection to [after], through the repairer or the planner given, and records the outcome. */
    suspend fun change(after: Set<ItemId>): Step {
        // As the selection's allocation does: today's days warmed before the turn, with the saved selection.
        repairer.warmDays(ACCOUNT, checkNotNull(source.savedSelection()), atMs)
        val pins =
            if (planner == null) {
                val outcome =
                    repairer.selectionChanged(ACCOUNT, after, atMs, 0L) { seq ->
                        chosen = after
                        applied = seq
                        true
                    }
                assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
                outcome.result.pins
            } else {
                direct(planner, after)
            }
        val record = checkNotNull(service.loaded(ACCOUNT)).repairs
        val bytes = checkNotNull(store.inner.peek(ACCOUNT)) { "the lineup file is stored" }
        val step = Step(pins, record, sha256(bytes))
        steps += step
        return step
    }

    // `TodayRepairer`'s turn for a change, as it is written in `changeInTurn`, without the clock, the probes and the
    // staleness check (nothing else runs), planned by [planner].
    private suspend fun direct(
        planner: Planner,
        after: Set<ItemId>,
    ): List<Pin> {
        val held = checkNotNull(service.loaded(ACCOUNT))
        checkNotNull(source.savedSelection())
        val (current, today) = source.chosenDays(ACCOUNT, Instant.fromEpochMilliseconds(atMs))
        val seq = held.repairs.nextSeq(current.seq)
        val change = SelectionChange(current.libraries, after, seq, atMs)
        val shared: SharedRead = { libraries, cuts -> source.sharedSnapshotsOf(ACCOUNT, libraries, cuts) }
        val planned = planner(PlanInputs(LineupDays(service.catalog), library, ACCOUNT, held, change, shared, today))
        val recorded =
            service.recordRepairsOutcome(ACCOUNT) { now -> planned.into(now.repairs, current.seq, current.resetSeq) }
        check(recorded == LineupService.Recorded.Saved) { "the record was written: $recorded" }
        service.saving(seq)
        try {
            chosen = after
            applied = seq
        } finally {
            service.saving(null)
        }
        return planned.pins
    }

    /** The last full day build's timing, for the benchmark's calibration against the TV's DayBuild line. */
    fun lastBuild(): DayBuild? = builds.lastOrNull()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        private const val TEN_MINUTES = 10 * 60_000L

        /** The synthetic library's genres (`SyntheticLibrary.GENRES`). */
        val GENRES = listOf("Action", "Comedy", "Drama", "Documentary", "Horror", "Music", "Sci-Fi")
    }
}
