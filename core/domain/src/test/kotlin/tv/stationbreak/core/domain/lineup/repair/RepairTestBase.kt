// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.ADDED_MS
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.DayBuild
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LIBRARY_ID
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.LineupSeams
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.MemoryDayStore
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.PersistedDays
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.RefreshMeta
import tv.stationbreak.core.domain.lineup.Saved
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.channelDays
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.leftovers
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.movie
import tv.stationbreak.core.domain.lineup.programmed
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.selectionOf
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.domain.lineup.timing
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.layoutTail
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The shared rig and fixtures of the repair engine's tests (spec 26, Task 2.5). */
internal open class RepairTestBase {
    protected val minute = 60_000L
    protected val hour = 60 * minute
    protected val noonMs = NOON.toEpochMilliseconds()
    protected val atMs = noonMs + 10 * minute

    protected val libA: ItemId = ItemId(LIBRARY_ID.value)
    protected val libB: ItemId = ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0")
    protected val libC: ItemId = ItemId("e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1")
    protected val libD: ItemId = ItemId("e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2")

    protected fun RuleItem.inLibrary(library: ItemId): RuleItem = copy(libraryId = CanonicalId.parse(library.value))

    protected fun film(
        n: Int,
        vararg genres: String,
        library: ItemId = libA,
        rating: String = "R",
        minutes: Long = 120,
    ): RuleItem = movie(n, *genres, rating = rating, runtimeMs = minutes * minute).inLibrary(library)

    /**
     * One TV: its library, its lineup store, a service, a source over the [chosen] libraries and a repairer. With
     * [disk] its days are kept on disk too; [sharedLibrary] is another TV's library (the same account's).
     */
    @Suppress("LongParameterList") // A TV's setup, one for one; all but the first three defaulted.
    protected inner class Rig(
        lineup: Lineup,
        items: List<RuleItem>,
        var chosen: Set<ItemId>,
        repairs: TodayRepairs = TodayRepairs.NONE,
        successors: Successors = Successors.None,
        private val restart: Rig? = null,
        disk: MemoryDayStore? = null,
        sharedLibrary: CountingLibrary? = null,
    ) {
        val library: CountingLibrary = restart?.library ?: sharedLibrary ?: CountingLibrary()

        /**
         * The seq saved with this TV's selection, as Part 4 keeps it: a seeded record counts as saved, and [change]
         * saves its own seq unless told not to.
         */
        var applied: Long = restart?.applied ?: repairs.latestSeq()

        /** When set, the saved selection cannot be read: the source's provider gives null. */
        @Volatile var unknown: Boolean = false

        @Volatile private var playableGiven = false

        /**
         * The playable libraries the source's provider gives with the selection (null: the selection is gone). It
         * follows [chosen] until a test sets it.
         */
        @Volatile var playable: Set<ItemId>? = null
            get() = if (playableGiven) field else chosen
            set(value) {
                field = value
                playableGiven = true
            }
        val store: RecordingStore = restart?.store ?: RecordingStore()
        private val clock =
            object : Clock {
                override fun now(): Instant = NOON
            }
        val events: MutableList<LineupEvent> = Collections.synchronizedList(mutableListOf())
        val service = LineupService(store, library, clock, { 0 }, { events += it }, LineupSeams(TestCatalog))
        val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
        val source =
            BuiltInScheduleSource(
                service,
                library,
                built = { builds += it },
                persisted = disk?.let { PersistedDays(it, "1:100", {}) },
            ) { if (unknown) null else selectionOf(chosen, applied, playable) }
        val repairer = TodayRepairer(service, source, library, successors)

        init {
            runBlocking {
                library.delegate.useAccount(ACCOUNT)
                if (restart == null) store.inner.save(ACCOUNT, LineupCodec.encode(lineup, RefreshMeta(), repairs))
            }
            if (items.isNotEmpty()) library.seed(items)
            // Every item has a current row (named), so it is live until a test tombstones it.
            for (item in items) {
                val row = MediaItem(ItemId(item.item.id.value), "Synthetic", ItemKind.Movie, null, null)
                library.delegate.seed(ACCOUNT, ItemId(item.libraryId.value), row)
            }
        }

        fun days(at: Long = atMs): List<ChannelDay> {
            val instant = Instant.fromEpochMilliseconds(at)
            return runBlocking { source.channelDays(ACCOUNT, chosen, instant) }
        }

        fun day(
            channel: ChannelId,
            at: Long = atMs,
        ): ChannelDay = days(at).first { it.channel.id == channel }

        /** The same TV after a restart: the same library and lineup store, a new service and source. */
        fun restarted(lineup: Lineup): Rig = Rig(lineup, emptyList(), chosen, restart = this)

        fun record(): TodayRepairs = runBlocking { assertNotNull(service.loaded(ACCOUNT)).repairs }

        /** [channel]'s repair as this TV shows it (at its saved seq), however many the record holds. */
        fun repairOf(channel: ChannelId): ChannelRepair {
            val shown = record().applied(applied).selection
            return shown.single { it.channel == channel }
        }

        /**
         * A change from [before] (the source's selection now) to [after]; the pins when recorded, else null. With
         * [save] the new selection is saved with the change's seq (the repair shows), as Part 4 does.
         */
        fun change(
            before: Set<ItemId>,
            after: Set<ItemId>,
            at: Long = atMs,
            save: Boolean = true,
        ): List<Pin>? = changed(before, after, at, save)?.pins

        /** [change], giving the recorded seq with the pins. */
        fun changed(
            before: Set<ItemId>,
            after: Set<ItemId>,
            at: Long = atMs,
            save: Boolean = true,
        ): SelectionRepaired? = (changeOutcome(before, after, at, save) as? RepairOutcome.Saved)?.result

        /**
         * A change through the repairer's whole protocol, as Part 4 runs it: [whileRecorded] runs inside the save
         * callback (the change recorded, the old libraries and seq still the saved ones), then the save writes the new
         * libraries and seq in one go when [save], or fails ([fail] thrown, else false) and the repairer undoes it.
         */
        fun changeOutcome(
            before: Set<ItemId>,
            after: Set<ItemId>,
            at: Long = atMs,
            save: Boolean = true,
            fail: Throwable? = null,
            whileRecorded: suspend (seq: Long) -> Unit = {},
        ): RepairOutcome<SelectionRepaired> {
            assertEquals(chosen, before, "the change is from the selection the source reads")
            return runBlocking {
                repairer.selectionChanged(ACCOUNT, after, at) { seq ->
                    whileRecorded(seq)
                    if (fail != null) throw fail
                    if (save) {
                        chosen = after
                        applied = seq
                    }
                    save
                }
            }
        }

        /**
         * How many blocks [TodayRepairer.repairLost] fixed with [playable] as the provider's playable libraries; 0 when
         * it saved nothing.
         */
        fun lost(
            playable: Set<ItemId>?,
            at: Long = atMs,
        ): Int {
            this.playable = playable
            return (runBlocking { repairer.repairLost(ACCOUNT, at) } as? RepairOutcome.Saved)?.result ?: 0
        }

        fun gone(id: CanonicalId) = library.delegate.tombstone(ACCOUNT, listOf(ItemId(id.value)))

        fun window(
            channel: ChannelId,
            from: Long,
            hours: Int = 4,
        ): List<AiringBlock> =
            runBlocking {
                val until = Instant.fromEpochMilliseconds(from + hours * hour)
                source.schedule(Instant.fromEpochMilliseconds(from), until, ScheduleBuild.AsNeeded)
            }.let { assertNotNull(it) }
                .rows
                .first { it.channel.id == channel }
                .blocks

        fun onAir(
            channel: ChannelId,
            at: Long,
        ) = runBlocking { source.onAir(channel, Instant.fromEpochMilliseconds(at), 2) }
    }

    protected fun custom(
        id: String,
        number: Int,
        rules: ChannelRules,
    ): LineupChannel = channel(id, number, ChannelState.On, ChannelSource.Custom(rules))

    protected fun ChannelRepair.tail(): List<KeptBlock> = blocks.filter { it.startMs >= pinEndMs }

    protected fun ChannelRepair.tailIds(): Set<CanonicalId> = tail().mapNotNullTo(HashSet()) { it.itemId }

    protected fun ChannelDay.blockAt(at: Long): ScheduleBlock = day.blocks.first { at >= it.startMs && at < it.endMs }

    protected fun ids(items: List<RuleItem>): Set<CanonicalId> = items.mapTo(HashSet()) { it.item.id }

    /** The blocks of [repair] lie end to end from its kept window's start to its day's end, none of them empty. */
    protected fun assertContiguous(repair: ChannelRepair) {
        var cursor = repair.keptFromMs
        for (block in repair.blocks) {
            assertEquals(cursor, block.startMs, "contiguous")
            assertTrue(block.endMs > block.startMs, "no block of no length")
            cursor = block.endMs
        }
        assertEquals(repair.dayEndMs, cursor, "to the day's end")
    }

    // --- A change of the selection ------------------------------------------------------------------------------

    protected val comedies = (0 until 7).map { film(100 + it, "Comedy") }
    protected val crimes = (0 until 7).map { film(120 + it, "Crime") }
    protected val comediesB = (0 until 2).map { film(300 + it, "Comedy", library = libB) }
    protected val crimesB = (0 until 2).map { film(320 + it, "Crime", library = libB) }

    protected val laughsRules = rules("Laughs", Source.Genre("Comedy"))
    protected val laughs = starter("laughs", 110, ChannelState.On, laughsRules)
    protected val whodunit = starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime")))

    protected fun kept(block: ScheduleBlock): KeptBlock =
        when (val c = block.content) {
            is ScheduleContent.Program -> KeptBlock(block.startMs, block.endMs, c.itemId, c.mediaStartOffsetMs)
            ScheduleContent.Slate -> KeptBlock(block.startMs, block.endMs, null, 0)
        }

    // A curated starter's view, as the build makes it (CuratedDaysTest's helper).
    protected fun curationChannel(
        key: String,
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): CurationChannel {
        val recipe = TestCatalog.recipes.first { it.key == key }
        val pool = PoolEvaluator.evaluate(rules, snapshot, PoolPolicy.GrownUp).items
        val settings = rules.toSchedulerSettings()
        return CurationChannel(ChannelId("starter:$key"), recipe.number, recipe, false, rules, settings, pool)
    }

    protected fun episode(
        n: Int,
        series: CanonicalId,
        number: Int,
        library: ItemId,
        genres: List<String> = listOf("Documentary"),
    ): RuleItem =
        RuleItem(
            ScheduleItem(id(n), ScheduleItemKind.Episode, hour, ADDED_MS, null, null, 2001, series, 1, number),
            CanonicalId.parse(library.value),
            null,
            "TV-14",
            genres,
            emptyList(),
        )

    protected val ownB = custom("custom-own", 601, rules("Own", Source.Library(LIB_B_ID)))

    // --- Lost blocks ----------------------------------------------------------------------------------------------

    protected val pinned = film(700, "Comedy", minutes = 60)
    protected val lost = film(701, "Comedy")
    protected val shortTail = film(702, "Comedy", minutes = 60)
    protected val admittedLong = film(703, "Comedy")
    protected val horrorFilm = film(704, "Horror")
    protected val kidsComedy = film(705, "Comedy", "Kids", rating = "G")
    protected val lostItems = listOf(pinned, lost, shortTail, admittedLong, horrorFilm, kidsComedy)

    /** Laughs' day repaired at noon: the pin 12:00-13:00, then [lost] 13:00-15:00 (from [offset]), then a slate. */
    protected fun lostRig(
        successors: Successors = Successors.None,
        offset: Long = 0,
        extra: List<KeptBlock> = emptyList(),
    ): Rig {
        val settings = laughsRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val end = Epochs.epochStart(settings, n + 1)
        val blocks =
            listOf(
                KeptBlock(noonMs, noonMs + hour, pinned.item.id, 0),
                KeptBlock(noonMs + hour, noonMs + 3 * hour, lost.item.id, offset),
                KeptBlock(noonMs + 3 * hour, noonMs + 4 * hour, shortTail.item.id, 0),
            ) + extra
        val last = blocks.last().endMs
        val repair =
            ChannelRepair(
                laughs.id,
                n,
                end,
                atMs,
                3,
                setOf(libA),
                noonMs,
                noonMs + hour,
                libA,
                blocks + KeptBlock(last, end, null, 0),
                null,
            )
        val rig = Rig(lineupOf(laughs), lostItems, setOf(libA), TodayRepairs(selection = listOf(repair)), successors)
        rig.gone(lost.item.id)
        return rig
    }

    protected val lostSlotStart get() = noonMs + hour
    protected val lostSlotEnd get() = noonMs + 3 * hour

    protected fun LostFix.isOfTheLostBlock(): Boolean {
        val slot = blockStartMs to blockEndMs
        return gone == lost.item.id && slot == lostSlotStart to lostSlotEnd
    }

    protected fun programmesBefore(
        day: ChannelDay,
        endMs: Long,
    ): Set<CanonicalId> =
        day.day.blocks
            .filter { it.startMs < endMs }
            .mapNotNullTo(HashSet()) { (it.content as? ScheduleContent.Program)?.itemId }
}

/** A library a change adds or removes. */
internal val LIB_B_ID: CanonicalId = CanonicalId.parse("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0")
