// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.ChannelRepair
import tv.stationbreak.core.domain.lineup.repair.KeptBlock
import tv.stationbreak.core.domain.lineup.repair.LostFix
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Epochs
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The day build follows the per-TV repair record (spec 26, Task 2.4): a repaired channel day comes from its record and
 * the channel's rules alone (no snapshot read, before the first tune too), recorded fixes apply to every day, what is
 * written to disk is the unrepaired build, and an unrepaired day is the day it always was.
 */
class RepairedDaysTest {
    private val library = CountingLibrary()
    private val lineups = RecordingStore()
    private val disk = MemoryDayStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val noonMs = NOON.toEpochMilliseconds()

    // A library that is not chosen on this TV: its rows stay (a hold), but no day reads it.
    private val untickedLibrary: CanonicalId = CanonicalId.parse("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0")

    private val laughsRules = rules("Laughs", Source.Genre("Comedy"))
    private val laughs = starter("laughs", 110, ChannelState.On, laughsRules)
    private val whodunitRules = rules("Whodunit", Source.Genre("Crime"))
    private val whodunit = starter("whodunit", 210, ChannelState.On, whodunitRules)

    // A custom channel on its own day start, so its day is of another cut-off.
    private val lateRules = rules("Late", Source.Genre("Crime"), timing = timing(start = "08:00"))
    private val late = channel("custom-late", 600, ChannelState.On, ChannelSource.Custom(lateRules))

    // A custom channel whose only source is the unticked library.
    private val ownRules = rules("Own", Source.Library(untickedLibrary))
    private val own = channel("custom-own", 601, ChannelState.On, ChannelSource.Custom(ownRules))

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        name((0 until 7).map { id(100 + it) } + (0 until 7).map { id(110 + it) })
        library.delegate.seed(
            ACCOUNT,
            ItemId(untickedLibrary.value),
            MediaItem(ItemId(id(400).value), "Synthetic", ItemKind.Movie, null, null),
        )
    }

    private fun name(ids: List<CanonicalId>) {
        val items = ids.map { MediaItem(ItemId(it.value), "Synthetic", ItemKind.Movie, null, null) }
        library.delegate.seed(ACCOUNT, LIBRARIES.first(), *items.toTypedArray())
    }

    private fun shortComedy(
        n: Int,
        minutes: Long,
    ): RuleItem = movie(n, "Comedy", rating = "R", runtimeMs = minutes * minute)

    /** One process: its own service and source over the shared stores, and what it reported. */
    private inner class Process(
        store: RecordingStore = lineups,
        saving: Boolean = true,
    ) {
        val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<DaySnapshotEvent> = Collections.synchronizedList(mutableListOf())
        val service = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = disk))
        val source =
            BuiltInScheduleSource(
                service,
                library,
                built = { builds += it },
                persisted = if (saving) PersistedDays(disk, "1:100", { events += it }) else null,
            ) { LIBRARIES }

        fun days(at: Instant = NOON): List<ChannelDay> =
            runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at).also { source.awaitSaves() } }

        fun day(
            channel: ChannelId,
            at: Instant = NOON,
        ): ChannelDay = days(at).first { it.channel.id == channel }

        fun onAir(
            channel: ChannelId,
            at: Instant,
            next: Int = 3,
        ) = runBlocking { source.onAir(channel, at, next) }

        fun airing(
            channel: ChannelId,
            at: Instant,
        ) = runBlocking { source.airings(at) }.first { it.id == channel }

        fun window(
            channel: ChannelId,
            from: Instant = NOON,
            hours: Int = 6,
        ): List<AiringBlock>? =
            runBlocking {
                val until = Instant.fromEpochMilliseconds(from.toEpochMilliseconds() + hours * hour)
                source.schedule(from, until, ScheduleBuild.AsNeeded)
            }?.rows
                ?.firstOrNull { it.channel.id == channel }
                ?.blocks

        fun record(repairs: TodayRepairs) = assertTrue(runBlocking { service.recordRepairs(ACCOUNT) { repairs } })

        fun refresh() = runBlocking { service.refresh(ACCOUNT, LIBRARIES) }
    }

    private fun store(
        lineup: Lineup,
        repairs: TodayRepairs = TodayRepairs.NONE,
        into: RecordingStore = lineups,
    ) = runBlocking { into.inner.save(ACCOUNT, LineupCodec.encode(lineup, RefreshMeta(), repairs)) }

    /** The day of [rules] that holds [atMs]: its number, start and end. */
    private fun dayOf(
        rules: ChannelRules,
        atMs: Long = noonMs,
    ): Triple<Long, Long, Long> {
        val settings = rules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val start = Epochs.epochStart(settings, n)
        return Triple(n, start, start + settings.epochMs)
    }

    /**
     * A repair of [channel]'s day [n] (`[startMs, endMs)`) made at [atMs]: the pin [pin] from the half hour before
     * [atMs] for an hour, then hour blocks of [tail] in turn, then a slate to the day's end ([slateTail]: a slate
     * straight after the pin).
     */
    @Suppress("LongParameterList")
    private fun repairOf(
        channel: ChannelId,
        day: Triple<Long, Long, Long>,
        atMs: Long = noonMs + 10 * minute,
        pin: CanonicalId = id(100),
        tail: List<CanonicalId> = listOf(id(101), id(102), id(103)),
        hidden: HiddenReason? = null,
        slateTail: Boolean = false,
        tailMs: Long = hour,
    ): ChannelRepair {
        val (n, _, end) = day
        val keptFrom = atMs - Math.floorMod(atMs, 30 * minute)
        val pinEnd = keptFrom + hour
        val blocks = arrayListOf(KeptBlock(keptFrom, pinEnd, pin, 5 * minute))
        var cursor = pinEnd
        var i = 0
        if (!slateTail) {
            while (cursor + 2 * tailMs <= end) {
                blocks += KeptBlock(cursor, cursor + tailMs, tail[i++ % tail.size], 0)
                cursor += tailMs
            }
        }
        blocks += KeptBlock(cursor, end, null, 0)
        return ChannelRepair(
            channel = channel,
            n = n,
            dayEndMs = end,
            atMs = atMs,
            seq = 1,
            libraries = LIBRARIES,
            keptFromMs = keptFrom,
            pinEndMs = pinEnd,
            pinLibrary = LIBRARIES.first(),
            blocks = blocks,
            tailHidden = hidden,
        )
    }

    /** What a repaired day is: a slate over the past, then the record's blocks. */
    private fun composedOf(
        repair: ChannelRepair,
        startMs: Long,
    ): List<ScheduleBlock> {
        val past = listOf(ScheduleBlock(startMs, repair.keptFromMs, ScheduleContent.Slate))
        return past +
            repair.blocks.map {
                val item = it.itemId
                val content: ScheduleContent =
                    if (item == null) ScheduleContent.Slate else ScheduleContent.Program(item, it.mediaStartMs)
                ScheduleBlock(it.startMs, it.endMs, content)
            }
    }

    private fun programAt(
        blocks: List<AiringBlock>,
        atMs: Long,
    ): Airing.Program = assertIs<Airing.Program>(blocks.first { atMs >= it.startMs && atMs < it.endMs }.airing)

    // --- The brief's tests -------------------------------------------------------------------------------------

    @Test
    fun coldStart_repairedDay_readsNoSnapshotBeforeFirstTune() {
        val day = dayOf(laughsRules)
        val repair = repairOf(laughs.id, day)
        store(lineupOf(laughs), TodayRepairs(selection = listOf(repair)))

        val cold = Process()
        val live = assertNotNull(cold.onAir(laughs.id, Instant.fromEpochMilliseconds(noonMs + 20 * minute)))

        assertEquals(id(100), assertIs<Airing.Program>(live.now.airing).itemId, "the pin is on air")
        assertNotNull(assertIs<Airing.Program>(live.now.airing).info)
        assertEquals(listOf(id(101), id(102), id(103)), live.next.map { assertIs<Airing.Program>(it.airing).itemId })
        assertEquals(emptyList(), library.snapshotCutoffs, "no snapshot was read")
        assertEquals(0, cold.source.snapshotReads())
        assertEquals(0, library.scheduleItemsOfCalls, "no gone block, so the tail pool is not read")
        assertEquals(composedOf(repair, day.second), cold.day(laughs.id).day.blocks)
    }

    @Test
    fun customChannelAllSourcesUnticked_hiddenLibraryNotChosen_afterItsProgramme() {
        val day = dayOf(ownRules)
        val repair = repairOf(own.id, day, pin = id(400), hidden = HiddenReason.LibraryNotChosen, slateTail = true)
        store(lineupOf(own), TodayRepairs(selection = listOf(repair)))
        val p = Process()
        val during = Instant.fromEpochMilliseconds(noonMs + 20 * minute)
        val after = Instant.fromEpochMilliseconds(repair.pinEndMs)

        assertNull(p.airing(own.id, during).hidden, "its programme still airs")
        assertEquals(id(400), assertIs<Airing.Program>(assertNotNull(p.onAir(own.id, during)).now.airing).itemId)
        assertEquals(HiddenReason.LibraryNotChosen, p.airing(own.id, after).hidden)
        assertNull(p.onAir(own.id, after), "hidden once its programme ends")
        assertNull(p.window(own.id, from = after), "and left out of the guide")
        val today = Instant.fromEpochMilliseconds(Epochs.cutoff(ownRules.toSchedulerSettings(), repair.n))
        assertTrue(today !in library.snapshotCutoffs, "the reason comes from the record: today's snapshot is not read")
    }

    @Test
    fun tomorrowBuildsFromTheNewSelection() {
        val today = dayOf(laughsRules)
        val ownRepair = repairOf(own.id, dayOf(ownRules), pin = id(400))
        val repairs = TodayRepairs(selection = listOf(repairOf(laughs.id, today), ownRepair))
        store(lineupOf(laughs, own), repairs)
        val p = Process(saving = false)
        val tomorrow = Instant.fromEpochMilliseconds(today.third + hour)
        assertEquals(composedOf(repairs.selection.first(), today.second), p.day(laughs.id).day.blocks)
        assertEquals(emptyList(), library.snapshotCutoffs)

        val days = p.days(tomorrow)

        val settings = laughsRules.toSchedulerSettings()
        assertEquals(
            listOf(Instant.fromEpochMilliseconds(Epochs.cutoff(settings, today.first + 1))),
            library.snapshotCutoffs.distinct(),
            "tomorrow is read from the library at its own cut-off",
        )
        assertEquals(HiddenReason.LibraryNotChosen, days.first { it.channel.id == own.id }.hidden)
        assertEquals(HiddenReason.LibraryNotChosen, p.airing(own.id, tomorrow).hidden)
        val fresh = Process(store = RecordingStore().also { store(lineupOf(laughs, own), into = it) }, saving = false)
        assertEquals(describe(fresh.days(tomorrow)), describe(days), "tomorrow is the day of the selection, no record")
    }

    @Test
    fun repairedDay_notSavedToDisk_unrepairedDaysUnchanged() {
        val repair = repairOf(laughs.id, dayOf(laughsRules))
        store(lineupOf(laughs, late), TodayRepairs(selection = listOf(repair)))
        val p = Process()

        val days = p.days()

        val laughsCut = Instant.fromEpochMilliseconds(Epochs.cutoff(laughsRules.toSchedulerSettings(), repair.n))
        assertTrue(laughsCut !in library.snapshotCutoffs, "the repaired day's cut-off is not read")
        assertTrue(p.events.none { it is DaySnapshotEvent.Saved }, "a set with a repaired day not built is not written")
        assertTrue(disk.files.isEmpty())
        val plain = Process(store = RecordingStore().also { store(lineupOf(laughs, late), into = it) }, saving = false)
        assertEquals(
            describe(plain.days().filter { it.channel.id == late.id }),
            describe(days.filter { it.channel.id == late.id }),
            "the unrepaired day is the day it always was",
        )
    }

    @Test
    fun persistedBytes_unchangedByARepair() {
        val first = Process()
        first.refresh()
        first.days()
        val (name, unrepaired) = disk.only()
        disk.files.clear()
        val laughsDay = first.day(LineupIds.starter("laughs"))
        val repair =
            repairOf(
                laughsDay.channel.id,
                Triple(
                    laughsDay.n,
                    laughsDay.epochStartMs,
                    laughsDay.epochStartMs + laughsDay.settings.epochMs,
                ),
            )
        first.record(TodayRepairs(selection = listOf(repair)))

        val next = Process()
        val days = next.days()

        val repaired = days.first { it.channel.id == repair.channel }
        assertEquals(composedOf(repair, laughsDay.epochStartMs), repaired.day.blocks)
        val (again, rewritten) = disk.only()
        assertEquals(name, again)
        assertContentEquals(unrepaired, rewritten, "what is written is the unrepaired build, byte for byte")
    }

    @Test
    fun restart_restoresUnrepairedSet_thenComposes_noSnapshotRead() {
        val first = Process()
        first.refresh()
        val before = first.days()
        val laughsDay = before.first { it.channel.id == LineupIds.starter("laughs") }
        val repair =
            repairOf(
                laughsDay.channel.id,
                Triple(
                    laughsDay.n,
                    laughsDay.epochStartMs,
                    laughsDay.epochStartMs + laughsDay.settings.epochMs,
                ),
            )
        first.record(TodayRepairs(selection = listOf(repair)))
        val reads = library.snapshotCutoffs.size

        val next = Process()
        val days = next.days()

        assertEquals("disk", next.builds.single().snapshot)
        assertEquals(reads, library.snapshotCutoffs.size, "no snapshot read")
        assertEquals(0, next.source.snapshotReads())
        val repaired = days.first { it.channel.id == repair.channel }
        assertEquals(composedOf(repair, laughsDay.epochStartMs), repaired.day.blocks)
        assertEquals(
            describe(before.filter { it.channel.id != repair.channel }),
            describe(days.filter { it.channel.id != repair.channel }),
        )
    }

    @Test
    fun sameRecord_sameDay_afterRestart() {
        val laughsDay = dayOf(laughsRules)
        val whodunitDay =
            Process(store = RecordingStore().also { store(lineupOf(laughs, whodunit), into = it) }, saving = false)
                .day(whodunit.id)
        val block = whodunitDay.day.whatsOn(noonMs + 3 * hour, 0).block
        val gone = assertIs<ScheduleContent.Program>(block.content).itemId
        val fix =
            LostFix(
                whodunit.id,
                whodunitDay.n,
                whodunitDay.epochStartMs + whodunitDay.settings.epochMs,
                block.startMs,
                block.endMs,
                gone,
                listOf(KeptBlock(block.startMs, block.startMs + hour, id(116).takeIf { it != gone } ?: id(115), 0)),
            )
        val repairs = TodayRepairs(selection = listOf(repairOf(laughs.id, laughsDay)), fixes = listOf(fix))
        store(lineupOf(laughs, whodunit), repairs)

        val one = Process()
        val laughsBefore = one.window(laughs.id)
        val whodunitBefore = one.window(whodunit.id)
        val two = Process()

        assertEquals(laughsBefore, two.window(laughs.id))
        assertEquals(whodunitBefore, two.window(whodunit.id))
        val airing = programAt(assertNotNull(whodunitBefore), block.startMs)
        assertEquals(fix.pieces.single().itemId, airing.itemId, "the fix applies")
    }

    @Test
    fun syncThatAddsARow_leavesTheTailIdentical() {
        val p = Process()
        p.refresh()
        val laughsDay = p.day(LineupIds.starter("laughs"))
        val repair =
            repairOf(
                laughsDay.channel.id,
                Triple(
                    laughsDay.n,
                    laughsDay.epochStartMs,
                    laughsDay.epochStartMs + laughsDay.settings.epochMs,
                ),
            )
        p.record(TodayRepairs(selection = listOf(repair)))
        val before = p.window(repair.channel)
        val generation = runBlocking { library.contentGeneration(ACCOUNT) }
        val builds = p.builds.size

        library.seed(movie(900, "Comedy", rating = "R"))
        name(listOf(id(900)))
        p.refresh()
        val after = p.window(repair.channel)

        assertNotEquals(generation, runBlocking { library.contentGeneration(ACCOUNT) }, "the generation moved")
        assertTrue(p.builds.size > builds, "the days were built again")
        assertEquals(before, after, "the repaired day's blocks are the same")
        assertTrue(assertNotNull(after).none { (it.airing as? Airing.Program)?.itemId == id(900) })
    }

    // --- Part 1's two hand-offs (controller ruling 1) ------------------------------------------------------------

    /** The unrepaired laughs day at noon, and the block on air then, from a build with no record. */
    private fun plainBlockAt(atMs: Long): Pair<ChannelDay, ScheduleBlock> {
        val plain = Process(store = RecordingStore().also { store(lineupOf(laughs), into = it) }, saving = false)
        val day = plain.day(laughs.id)
        return day to day.day.whatsOn(atMs, 0).block
    }

    @Test
    fun fixRemainder_staysUnnamed_neverFilledAgain() {
        library.seed(shortComedy(330, 20))
        name(listOf(id(330)))
        val (day, block) = plainBlockAt(noonMs)
        val gone = assertIs<ScheduleContent.Program>(block.content).itemId
        val piece = (0 until 7).map { id(100 + it) }.first { it != gone }
        library.delegate.tombstone(ACCOUNT, listOf(ItemId(gone.value)))
        val fix =
            LostFix(
                laughs.id,
                day.n,
                day.epochStartMs + day.settings.epochMs,
                block.startMs,
                block.endMs,
                gone,
                listOf(KeptBlock(block.startMs, block.startMs + 30 * minute, piece, 0)),
            )
        store(lineupOf(laughs), TodayRepairs(fixes = listOf(fix)))
        library.currentIdsCalls = 0

        val from = Instant.fromEpochMilliseconds(block.startMs)
        val cells = assertNotNull(Process().window(laughs.id, from = from, hours = 1))

        val inBlock = cells.filter { it.startMs >= block.startMs && it.endMs <= block.endMs }
        assertEquals(piece, programAt(inBlock, block.startMs).itemId)
        val rest = inBlock.last()
        assertEquals(block.startMs + 30 * minute, rest.startMs)
        assertEquals(block.endMs, rest.endMs)
        val program = assertIs<Airing.Program>(rest.airing)
        assertEquals(gone, program.itemId)
        assertNull(program.info, "the remainder of a fix stays unnamed, though a 20-minute item would fit it")
        assertEquals(0, library.currentIdsCalls, "and no fill was worked out for it")
    }

    @Test
    fun goneFixPiece_isFilledWithoutThatFixsPieces() {
        library.seed(shortComedy(331, 20), shortComedy(332, 40))
        name(listOf(id(331), id(332)))
        val (day, block) = plainBlockAt(noonMs)
        val gone = assertIs<ScheduleContent.Program>(block.content).itemId
        library.delegate.tombstone(ACCOUNT, listOf(ItemId(gone.value), ItemId(id(332).value)))
        // The fix: a 40-minute piece (now gone too), then a 20-minute one.
        val fix =
            LostFix(
                laughs.id,
                day.n,
                day.epochStartMs + day.settings.epochMs,
                block.startMs,
                block.endMs,
                gone,
                listOf(
                    KeptBlock(block.startMs, block.startMs + 40 * minute, id(332), 0),
                    KeptBlock(block.startMs + 40 * minute, block.startMs + 60 * minute, id(331), 0),
                ),
            )
        store(lineupOf(laughs), TodayRepairs(fixes = listOf(fix)))

        val from = Instant.fromEpochMilliseconds(block.startMs)
        val cells = assertNotNull(Process().window(laughs.id, from = from, hours = 1))

        val goneSpan = cells.filter { it.startMs >= block.startMs && it.endMs <= block.startMs + 40 * minute }
        assertTrue(goneSpan.isNotEmpty())
        assertTrue(
            goneSpan.none { (it.airing as? Airing.Program)?.itemId == id(331) },
            "the fix's own 20-minute piece is never its fill, though it fits",
        )
        assertEquals(id(331), programAt(cells, block.startMs + 45 * minute).itemId, "the fix's second piece airs")
    }

    // --- Reads (controller rulings 3, 4, 5) ----------------------------------------------------------------------

    @Test
    fun unrepairedDay_buildMakesNoExtraReads() {
        fun reads(repairs: TodayRepairs): List<Int> {
            val store = RecordingStore().also { store(lineupOf(laughs, whodunit), repairs, into = it) }
            library.snapshotCutoffs.clear()
            library.programInfoCalls = 0
            library.currentIdsCalls = 0
            library.scheduleItemsOfCalls = 0
            assertNotNull(Process(store = store, saving = false).onAir(whodunit.id, NOON))
            return with(library) {
                listOf(snapshotCutoffs.size, programInfoCalls, currentIdsCalls, scheduleItemsOfCalls)
            }
        }
        val none = reads(TodayRepairs.NONE)
        val neighbourRepaired = reads(TodayRepairs(selection = listOf(repairOf(laughs.id, dayOf(laughsRules)))))

        assertEquals(listOf(1, 1, 0, 0), none)
        assertEquals(none, neighbourRepaired, "a repaired neighbour adds no read to an unrepaired day's tune")
    }

    @Test
    fun aRecordChange_rebuildsExactlyThatChannelDay() {
        store(lineupOf(laughs, whodunit))
        val p = Process(saving = false)
        val before = p.days()
        val reads = library.snapshotCutoffs.size
        val repair = repairOf(laughs.id, dayOf(laughsRules))

        p.record(TodayRepairs(selection = listOf(repair)))
        val after = p.days()

        assertSame(before.first { it.channel.id == whodunit.id }, after.first { it.channel.id == whodunit.id })
        val repaired = after.first { it.channel.id == laughs.id }
        assertEquals(composedOf(repair, dayOf(laughsRules).second), repaired.day.blocks)
        assertEquals(reads, library.snapshotCutoffs.size, "nothing was read again")
        assertEquals(2, p.builds.size)
    }

    @Test
    fun repairedDay_goneTailBlock_fillsFromTheTailPool_readByIdOnce() {
        library.seed(shortComedy(340, 30), shortComedy(341, 30), shortComedy(342, 30))
        name(listOf(id(340), id(341)))
        // id(342) has no current row: its tail blocks are gone, and are filled from the tail pool alone.
        val repair = repairOf(laughs.id, dayOf(laughsRules), tail = listOf(id(340), id(341), id(342)))
        store(lineupOf(laughs), TodayRepairs(selection = listOf(repair)))
        val goneBlock = repair.blocks.first { it.itemId == id(342) }
        val p = Process()

        val first = assertNotNull(p.window(laughs.id))
        val second = assertNotNull(p.window(laughs.id))

        assertEquals(first, second)
        val filled = first.filter { it.startMs >= goneBlock.startMs && it.endMs <= goneBlock.endMs }
        val names = filled.mapNotNull { (it.airing as? Airing.Program)?.takeIf { p -> p.info != null }?.itemId }
        assertEquals(setOf(id(340), id(341)), names.toSet(), "two tail items fill the hour")
        assertEquals(2, names.size)
        assertEquals(1, library.scheduleItemsOfCalls, "the tail pool is read by id once")
        assertEquals(emptyList(), library.snapshotCutoffs, "never the snapshot")
    }

    @Test
    fun libraryNotChosen_comesBeforeNoMatches_inABuiltDay() {
        val days = LineupDays(TestCatalog)
        val lineup = lineupOf(own)
        val scheduled = days.scheduled(lineup, noonMs, null)
        val cutoff = Instant.fromEpochMilliseconds(scheduled.single().cutoffMs)
        val snapshot = runBlocking { library.ruleSnapshot(ACCOUNT, LIBRARIES, cutoff) }

        val chosen = days.days(lineup, scheduled, mapOf(scheduled.single().cutoffMs to snapshot), libraries = LIBRARIES)
        val unknown = days.days(lineup, scheduled, mapOf(scheduled.single().cutoffMs to snapshot))

        assertEquals(HiddenReason.LibraryNotChosen, chosen.single().hidden)
        assertEquals(HiddenReason.NoMatches, unknown.single().hidden, "without the libraries, as before")
    }
}
