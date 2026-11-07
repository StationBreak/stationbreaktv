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
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LIBRARY_ID
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupSeams
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.RefreshMeta
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.leftovers
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.movie
import tv.stationbreak.core.domain.lineup.programmed
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.domain.lineup.timing
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.layoutTail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The repair engine (spec 26, Task 2.5): a change of the selection pins every channel's programme on air and records
 * a re-planned tail; a block whose item left the library is matched or filled, and the pieces recorded.
 */
class TodayRepairerTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val noonMs = NOON.toEpochMilliseconds()
    private val atMs = noonMs + 10 * minute

    private val libA: ItemId = ItemId(LIBRARY_ID.value)
    private val libB: ItemId = ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0")
    private val libC: ItemId = ItemId("e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1")
    private val libD: ItemId = ItemId("e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2")

    private fun RuleItem.inLibrary(library: ItemId): RuleItem = copy(libraryId = CanonicalId.parse(library.value))

    private fun film(
        n: Int,
        vararg genres: String,
        library: ItemId = libA,
        rating: String = "R",
        minutes: Long = 120,
    ): RuleItem = movie(n, *genres, rating = rating, runtimeMs = minutes * minute).inLibrary(library)

    /** One TV: its library, its lineup store, a service, a source over the [chosen] libraries and a repairer. */
    private inner class Rig(
        lineup: Lineup,
        items: List<RuleItem>,
        var chosen: Set<ItemId>,
        repairs: TodayRepairs = TodayRepairs.NONE,
        successors: Successors = Successors.None,
        private val restart: Rig? = null,
    ) {
        val library: CountingLibrary = restart?.library ?: CountingLibrary()
        val store: RecordingStore = restart?.store ?: RecordingStore()
        private val clock =
            object : Clock {
                override fun now(): Instant = NOON
            }
        val service = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog))
        val source = BuiltInScheduleSource(service, library) { chosen }
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

        fun repairOf(channel: ChannelId): ChannelRepair = record().selection.single { it.channel == channel }

        fun change(
            before: Set<ItemId>,
            after: Set<ItemId>,
            seq: Long = 7,
            at: Long = atMs,
        ): List<Pin>? {
            val pins = runBlocking { repairer.selectionChanged(ACCOUNT, before, after, seq, at) }
            chosen = after
            return pins
        }

        fun lost(
            playable: Set<ItemId>?,
            at: Long = atMs,
        ): Int = runBlocking { repairer.repairLost(ACCOUNT, playable, at) }

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

    private fun custom(
        id: String,
        number: Int,
        rules: ChannelRules,
    ): LineupChannel = channel(id, number, ChannelState.On, ChannelSource.Custom(rules))

    private fun ChannelRepair.tail(): List<KeptBlock> = blocks.filter { it.startMs >= pinEndMs }

    private fun ChannelRepair.tailIds(): Set<CanonicalId> = tail().mapNotNullTo(HashSet()) { it.itemId }

    private fun ChannelDay.blockAt(at: Long): ScheduleBlock = day.blocks.first { at >= it.startMs && at < it.endMs }

    private fun ids(items: List<RuleItem>): Set<CanonicalId> = items.mapTo(HashSet()) { it.item.id }

    /** The blocks of [repair] lie end to end from its kept window's start to its day's end, none of them empty. */
    private fun assertContiguous(repair: ChannelRepair) {
        var cursor = repair.keptFromMs
        for (block in repair.blocks) {
            assertEquals(cursor, block.startMs, "contiguous")
            assertTrue(block.endMs > block.startMs, "no block of no length")
            cursor = block.endMs
        }
        assertEquals(repair.dayEndMs, cursor, "to the day's end")
    }

    // --- A change of the selection ------------------------------------------------------------------------------

    private val comedies = (0 until 7).map { film(100 + it, "Comedy") }
    private val crimes = (0 until 7).map { film(120 + it, "Crime") }
    private val comediesB = (0 until 2).map { film(300 + it, "Comedy", library = libB) }
    private val crimesB = (0 until 2).map { film(320 + it, "Crime", library = libB) }

    private val laughsRules = rules("Laughs", Source.Genre("Comedy"))
    private val laughs = starter("laughs", 110, ChannelState.On, laughsRules)
    private val whodunit = starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime")))

    @Test
    fun selectionChanged_pinsEveryChannelsCurrentProgramme_recordsTail() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA, libB))
        val before = rig.days()
        val pins = before.associate { it.channel.id to it.blockAt(atMs) }

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA), seq = 7))

        for (day in before) {
            val repair = rig.repairOf(day.channel.id)
            val pin = pins.getValue(day.channel.id)
            assertEquals(day.n, repair.n)
            assertEquals(day.epochEndMs, repair.dayEndMs)
            assertEquals(atMs, repair.atMs)
            assertEquals(7, repair.seq)
            assertEquals(setOf(libA), repair.libraries)
            assertEquals(noonMs, repair.keptFromMs, "the kept window starts at the half hour")
            assertEquals(pin.endMs, repair.pinEndMs)
            val pinItem = (pin.content as ScheduleContent.Program).itemId
            assertEquals(if (pinItem in ids(comediesB + crimesB)) libB else libA, repair.pinLibrary)
            assertNull(repair.tailHidden)
            assertContiguous(repair)
            assertEquals(pin, rig.day(day.channel.id).blockAt(atMs), "the programme on air keeps its slot")
            assertTrue(repair.tailIds().none { it in ids(comediesB + crimesB) }, "nothing of the removed library")
        }
        // The tail is the scheduler's own, from the pin's end, over the day pool without the removed library.
        val day = before.first { it.channel.id == laughs.id }
        val repair = rig.repairOf(laughs.id)
        val pool = day.pool.filter { it.id !in ids(comediesB) }
        val aired =
            day.day.blocks
                .filter { it.startMs < repair.pinEndMs }
                .mapNotNullTo(HashSet()) { (it.content as? ScheduleContent.Program)?.itemId }
        val expected = layoutTail(day.settings, pool, day.n, day.films, day.weights, repair.pinEndMs, aired)
        assertEquals(expected.map { kept(it) }, repair.tail())
    }

    private fun kept(block: ScheduleBlock): KeptBlock =
        when (val c = block.content) {
            is ScheduleContent.Program -> KeptBlock(block.startMs, block.endMs, c.itemId, c.mediaStartOffsetMs)
            ScheduleContent.Slate -> KeptBlock(block.startMs, block.endMs, null, 0)
        }

    @Test
    fun selectionChanged_noTitleChangesChannel() {
        val westerns = (0 until 4).map { film(140 + it, "Western") }
        val horror = (0 until 3).map { film(160 + it, "Horror") }
        val custom = custom("custom-1", 600, rules("Mine", Source.Genre("Horror")))
        val lineup =
            lineupOf(
                laughs,
                whodunit,
                starter("westerns", 220, ChannelState.On, rules("Westerns", Source.Genre("Western"))),
                custom,
                leftovers("odds-and-ends", 199, null),
            )
        val docs = (0 until 3).map { film(180 + it, "Documentary") }
        val rig = Rig(lineup, comedies + crimes + westerns + horror + docs + comediesB + crimesB, setOf(libA, libB))
        val before = rig.days().associate { it.channel.id to it.pool.mapTo(HashSet()) { item -> item.id } }

        rig.change(before = setOf(libA, libB), after = setOf(libA))

        for ((channel, pool) in before) {
            val tail = rig.repairOf(channel).tailIds()
            assertTrue(tail.isNotEmpty())
            assertTrue(pool.containsAll(tail), "every title of $channel's tail was its own already")
        }
    }

    // A curated starter's view, as the build makes it (CuratedDaysTest's helper).
    private fun curationChannel(
        key: String,
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): CurationChannel {
        val recipe = TestCatalog.recipes.first { it.key == key }
        val pool = PoolEvaluator.evaluate(rules, snapshot, PoolPolicy.GrownUp).items
        val settings = rules.toSchedulerSettings()
        return CurationChannel(ChannelId("starter:$key"), recipe.number, recipe, false, rules, settings, pool)
    }

    @Test
    fun addedTitle_homedByCurationScore_renumberingMovesNothing() {
        val curatedLaughs = programmed("Laughs", Source.Genre("Comedy"))
        val curatedWhodunit = programmed("Whodunit", Source.Genre("Crime"))
        val both = film(1300, "Comedy", "Crime", library = libB)
        val items = comedies.take(4) + crimes.take(4) + both

        fun homeAfter(
            laughsNumber: Int,
            whodunitNumber: Int,
        ): Set<ChannelId> {
            val lineup =
                lineupOf(
                    starter("laughs", laughsNumber, ChannelState.On, curatedLaughs),
                    starter("whodunit", whodunitNumber, ChannelState.On, curatedWhodunit),
                )
            val rig = Rig(lineup, items, setOf(libA))
            assertNotNull(rig.change(before = setOf(libA), after = setOf(libA, libB)))
            return rig
                .record()
                .selection
                .filter { both.item.id in it.tailIds() }
                .mapTo(HashSet()) { it.channel }
        }

        val settings = curatedLaughs.toSchedulerSettings()
        val cutoff = Epochs.cutoff(settings, Epochs.epochNumber(settings, atMs))
        val snapshot =
            runBlocking {
                val lib = CountingLibrary().also { it.seed(items) }
                lib.delegate.ruleSnapshot(ACCOUNT, setOf(libA, libB), Instant.fromEpochMilliseconds(cutoff))
            }
        val homes =
            Curation.homes(
                snapshot,
                listOf(
                    curationChannel("laughs", curatedLaughs, snapshot),
                    curationChannel("whodunit", curatedWhodunit, snapshot),
                ),
            )
        val home = assertNotNull(homes.homeOf(both.item.id))
        assertEquals(setOf(home), homeAfter(110, 210), "the added title airs on its home alone")
        assertEquals(setOf(home), homeAfter(250, 120), "a renumber moves no home")
    }

    @Test
    fun addedTitle_customAndEditedChannelsTakeItWhenRulesAdmit() {
        val curated = starter("laughs", 110, ChannelState.On, programmed("Laughs", Source.Genre("Comedy")))
        val mine = custom("custom-1", 600, rules("Mine", Source.Genre("Comedy")))
        val editedRules = rules("Whodunit", Source.Genre("Comedy"))
        val edited =
            channel(
                "starter:whodunit",
                210,
                ChannelState.On,
                ChannelSource.Starter("whodunit", edited = true, rules = editedRules),
            )
        val added = film(1300, "Comedy", library = libB)
        val rig = Rig(lineupOf(curated, mine, edited), comedies.take(4) + added, setOf(libA))

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        for (channel in listOf(curated, mine, edited)) {
            assertTrue(added.item.id in rig.repairOf(channel.id).tailIds(), "${channel.id} takes the added title")
        }
    }

    @Test
    fun addedTitle_leftoversTakeOnlyWhatNoneTook() {
        val odds = leftovers("odds-and-ends", 199, null)
        val comedy = film(1300, "Comedy", library = libB)
        val doc = film(1410, "Documentary", library = libB)
        val rig = Rig(lineupOf(laughs, odds), comedies.take(3) + crimes.take(2) + comedy + doc, setOf(libA))

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        assertTrue(comedy.item.id in rig.repairOf(laughs.id).tailIds())
        val leftoversTail = rig.repairOf(odds.id).tailIds()
        assertTrue(doc.item.id in leftoversTail, "what no channel took goes to the leftovers")
        assertTrue(comedy.item.id !in leftoversTail, "what a channel took does not")
    }

    private fun episode(
        n: Int,
        series: CanonicalId,
        number: Int,
        library: ItemId,
    ): RuleItem =
        RuleItem(
            ScheduleItem(id(n), ScheduleItemKind.Episode, hour, ADDED_MS, null, null, 2001, series, 1, number),
            CanonicalId.parse(library.value),
            null,
            "TV-14",
            listOf("Documentary"),
            emptyList(),
        )

    @Test
    fun addedItems_ofATitleOnTheAirToday_stayWithItsChannel() {
        val odds = leftovers("odds-and-ends", 199, null)
        val series = id(1600)
        val todays = (1..3).map { episode(1600 + it, series, it, libA) }
        val newOnes = (4..5).map { episode(1600 + it, series, it, libB) }
        val rig = Rig(lineupOf(laughs, odds), comedies.take(3) + todays + newOnes, setOf(libA))
        assertTrue(rig.day(odds.id).pool.any { it.groupId == series }, "the series airs on the leftovers today")

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        assertTrue(rig.repairOf(odds.id).tailIds().containsAll(ids(newOnes)), "its new episodes join it there")
    }

    @Test
    fun repairedLeftovers_matchBuildClaims() {
        val odds = leftovers("odds-and-ends", 199, null)
        val docs = custom("custom-docs", 600, rules("Docs", Source.Genre("Documentary")))
        val lineup = lineupOf(laughs, docs, odds)
        val addedItems =
            listOf(film(1300, "Comedy", library = libB), film(1410, "Documentary", library = libB)) +
                film(1200, "Western", library = libB)
        val rig = Rig(lineup, comedies.take(3) + crimes.take(2) + addedItems, setOf(libA))

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        // Tomorrow's build over the new selection: what the leftovers' day takes of the added library.
        val lineupDays = LineupDays(TestCatalog)
        val scheduled = lineupDays.scheduled(lineup, atMs, null)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                val cutoff = Instant.fromEpochMilliseconds(it)
                runBlocking { rig.library.delegate.ruleSnapshot(ACCOUNT, setOf(libA, libB), cutoff) }
            }
        val built = lineupDays.days(lineup, scheduled, snapshots).first { it.channel.id == odds.id }
        val expected = built.pool.mapTo(HashSet()) { it.id }.intersect(ids(addedItems))
        assertEquals(setOf(id(1200)), expected, "the custom channel's claim keeps the documentary from the leftovers")
        assertEquals(expected, rig.repairOf(odds.id).tailIds().intersect(ids(addedItems)))
    }

    @Test
    fun secondChangeSameDay_poolIsRecordedUnairedTail_minusRemoved_plusAdded() {
        val mine = custom("custom-1", 600, rules("Mine", Source.Genre("Comedy")))
        val comediesC = listOf(film(500, "Comedy", library = libC))
        val comediesD = listOf(film(520, "Comedy", library = libD))
        val rig = Rig(lineupOf(mine), comedies.take(4) + comediesB + comediesC + comediesD, setOf(libA, libB))
        rig.change(before = setOf(libA, libB), after = setOf(libA, libB, libC))
        val first = rig.repairOf(mine.id)
        val second = atMs + 4 * hour
        val unaired = first.tail().filter { it.startMs >= second }.mapNotNullTo(HashSet()) { it.itemId }

        assertNotNull(rig.change(before = setOf(libA, libB, libC), after = setOf(libA, libC, libD), at = second))

        val repair = rig.repairOf(mine.id)
        assertEquals(second, repair.atMs, "the latest repair of the day replaces the first")
        val tail = repair.tailIds()
        assertTrue(tail.none { it in ids(comediesB) }, "the removed library is gone from the tail")
        assertTrue(comediesD.single().item.id in tail, "the added title is in it")
        assertTrue((unaired - ids(comediesB) + ids(comediesD)).containsAll(tail), "the base is the unaired tail")
    }

    private val ownB = custom("custom-own", 601, rules("Own", Source.Library(LIB_B_ID)))

    @Test
    fun tailHiddenStored_noSnapshotForHiddenReason() {
        val rig = Rig(lineupOf(laughs, ownB), comedies + comediesB, setOf(libA, libB))
        rig.change(before = setOf(libA, libB), after = setOf(libA))
        val repair = rig.repairOf(ownB.id)
        assertEquals(HiddenReason.LibraryNotChosen, repair.tailHidden)

        val cold = rig.restarted(lineupOf(laughs, ownB))
        rig.library.snapshotCutoffs.clear()
        val airing = runBlocking { cold.source.airings(Instant.fromEpochMilliseconds(repair.pinEndMs)) }

        assertEquals(HiddenReason.LibraryNotChosen, airing.first { it.id == ownB.id }.hidden)
        assertEquals(emptyList(), rig.library.snapshotCutoffs, "the reason is read from the record")
    }

    @Test
    fun selectionChanged_returnsPinsOfRemovedLibraries() {
        val ownA = custom("custom-a", 602, rules("A", Source.Library(LIBRARY_ID)))
        val rig = Rig(lineupOf(ownA, ownB), comedies + comediesB, setOf(libA, libB))
        val pinB = rig.day(ownB.id).blockAt(atMs)

        val pins = assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        assertEquals(listOf(Pin(libB, pinB.endMs)), pins)
        assertEquals(libB, rig.repairOf(ownB.id).pinLibrary)
        assertEquals(libA, rig.repairOf(ownA.id).pinLibrary)
    }

    @Test
    fun customDayStart_pinsItsOwnProgramme() {
        val lateRules = rules("Late", Source.Genre("Crime"), timing = timing(start = "08:30"))
        val late = custom("custom-late", 600, lateRules)
        val rig = Rig(lineupOf(laughs, late), comedies + crimes + crimesB, setOf(libA, libB))
        val day = rig.day(late.id)
        val pin = day.blockAt(atMs)
        val settings = lateRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)

        rig.change(before = setOf(libA, libB), after = setOf(libA))

        val repair = rig.repairOf(late.id)
        assertEquals(n, repair.n)
        assertEquals(Epochs.epochStart(settings, n + 1), repair.dayEndMs, "its own day ends at its own start")
        assertEquals(pin.endMs, repair.pinEndMs)
        assertContiguous(repair)
        assertTrue(pin.startMs < repair.keptFromMs, "the programme began before the kept window")
        assertEquals(positionAt(pin, atMs), positionAt(rig.day(late.id).blockAt(atMs), atMs), "it airs on, unchanged")
    }

    // What airs at [at] in [block]: the item, where in it, and when the block ends.
    private fun positionAt(
        block: ScheduleBlock,
        at: Long,
    ): Triple<CanonicalId, Long, Long> {
        val program = assertIs<ScheduleContent.Program>(block.content)
        return Triple(program.itemId, program.mediaStartOffsetMs + (at - block.startMs), block.endMs)
    }

    @Test
    fun kidAndGrownUpHolds_applyToTheTail() {
        val toons = starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G"))
        val kidsA = (0 until 3).map { film(200 + it, "Animation", "Kids", rating = "G") }
        val forKids = film(1500, "Comedy", "Animation", "Kids", library = libB, rating = "G")
        val grownAnimation = film(1501, "Animation", library = libB, rating = "R")
        val rig = Rig(lineupOf(toons, laughs), comedies.take(3) + kidsA + forKids + grownAnimation, setOf(libA))

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        assertTrue(forKids.item.id in rig.repairOf(toons.id).tailIds(), "the kid channel takes the kids' title")
        assertTrue(forKids.item.id !in rig.repairOf(laughs.id).tailIds(), "the grown-up hold keeps it off laughs")
        assertTrue(grownAnimation.item.id !in rig.repairOf(toons.id).tailIds(), "the kid hold keeps the R film off")
    }

    // --- Lost blocks ----------------------------------------------------------------------------------------------

    private val pinned = film(700, "Comedy", minutes = 60)
    private val lost = film(701, "Comedy")
    private val shortTail = film(702, "Comedy", minutes = 60)
    private val admittedLong = film(703, "Comedy")
    private val horrorFilm = film(704, "Horror")
    private val kidsComedy = film(705, "Comedy", "Kids", rating = "G")
    private val lostItems = listOf(pinned, lost, shortTail, admittedLong, horrorFilm, kidsComedy)

    /** Laughs' day repaired at noon: the pin 12:00-13:00, then [lost] 13:00-15:00 (from [offset]), then a slate. */
    private fun lostRig(
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

    private val lostSlotStart get() = noonMs + hour
    private val lostSlotEnd get() = noonMs + 3 * hour

    private fun LostFix.isOfTheLostBlock(): Boolean {
        val slot = blockStartMs to blockEndMs
        return gone == lost.item.id && slot == lostSlotStart to lostSlotEnd
    }

    @Test
    fun goneProgrammeOnAir_keepsTheFillItShows_andItsRestStaysUnnamed() {
        val settings = laughsRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val start = Epochs.epochStart(settings, n)
        val end = Epochs.epochStart(settings, n + 1)
        val short = film(710, "Comedy", minutes = 90)
        val blocks =
            listOf(
                KeptBlock(start, noonMs, null, 0),
                KeptBlock(noonMs, noonMs + 2 * hour, lost.item.id, 0),
                KeptBlock(noonMs + 2 * hour, noonMs + 2 * hour + 90 * minute, short.item.id, 0),
                KeptBlock(noonMs + 2 * hour + 90 * minute, end, null, 0),
            )
        val repair = ChannelRepair(laughs.id, n, end, atMs, 3, setOf(libA), start, noonMs, null, blocks, null)
        val rig = Rig(lineupOf(laughs), comedies.take(2) + lost + short, setOf(libA), TodayRepairs(listOf(repair)))
        rig.gone(lost.item.id)
        val rest = noonMs + 90 * minute
        val shown = assertNotNull(rig.onAir(laughs.id, atMs))
        assertEquals(short.item.id, assertIs<Airing.Program>(shown.now.airing).itemId, "the fill airs in its place")
        val restShown = assertNotNull(rig.onAir(laughs.id, rest + minute)).now

        assertNotNull(rig.change(before = setOf(libA), after = setOf(libA)))

        assertEquals(shown.now, assertNotNull(rig.onAir(laughs.id, atMs)).now, "what airs goes on airing")
        assertEquals(restShown, assertNotNull(rig.onAir(laughs.id, rest + minute)).now, "the rest is never filled")
        assertNull(assertIs<Airing.Program>(restShown.airing).info)
        val fix = rig.record().fixes.single()
        assertEquals(rest to noonMs + 2 * hour, fix.blockStartMs to fix.blockEndMs)
        assertEquals(emptyList(), fix.pieces, "an empty fix: the rest stays unnamed")
    }

    @Test
    fun repairLost_onRepairedDay_fillsFromTailPoolPlusAdmitted_recorded() {
        val rig = lostRig()

        assertEquals(1, rig.lost(setOf(libA)))

        val fix = rig.record().fixes.single()
        assertTrue(fix.isOfTheLostBlock())
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotEnd, admittedLong.item.id, 0)), fix.pieces)
        val cold = rig.restarted(lineupOf(laughs))
        val onAir = assertNotNull(cold.onAir(laughs.id, lostSlotStart + minute))
        assertEquals(admittedLong.item.id, assertIs<Airing.Program>(onAir.now.airing).itemId, "read from the record")
    }

    @Test
    fun repairLost_whenGone_usesRecordedTailOnly() {
        val rig = lostRig()
        val reads = rig.library.snapshotCutoffs.size

        assertEquals(1, rig.lost(playable = null))

        val fix = rig.record().fixes.single()
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotStart + hour, shortTail.item.id, 0)), fix.pieces)
        assertEquals(reads, rig.library.snapshotCutoffs.size, "no library read for a fill")
    }

    @Test
    fun repairLost_fillsGoneBlocks_sameAsTheNamingFill() {
        val mixed = comedies.take(4) + listOf(film(150, "Comedy", minutes = 50), film(151, "Comedy", minutes = 30))
        val rig = Rig(lineupOf(laughs), mixed, setOf(libA))
        val day = rig.day(laughs.id)
        val later = day.day.blocks.first { it.startMs > atMs + hour && it.content is ScheduleContent.Program }
        val goneId = (later.content as ScheduleContent.Program).itemId
        rig.gone(goneId)
        val named = rig.window(laughs.id, later.startMs, hours = 1).filter { it.startMs < later.endMs }

        val goneBlocks =
            day.day.blocks.count { it.endMs > atMs && (it.content as? ScheduleContent.Program)?.itemId == goneId }
        assertEquals(goneBlocks, rig.lost(setOf(libA)), "every gone block ahead is fixed")

        val fix = rig.record().fixes.single { it.blockStartMs == later.startMs }
        assertEquals(later.endMs, fix.blockEndMs)
        val namedPieces = named.filter { (it.airing as? Airing.Program)?.info != null }
        assertEquals(
            namedPieces.map { Triple(it.startMs, it.endMs, (it.airing as Airing.Program).itemId) },
            fix.pieces.map { Triple(it.startMs, it.endMs, it.itemId) },
        )
        assertEquals(named, rig.window(laughs.id, later.startMs, hours = 1).filter { it.startMs < later.endMs })
    }

    @Test
    fun repairLost_matchFailingHoldOrRules_isFilledInstead() {
        val secondLost = film(706, "Comedy")
        val extra = listOf(KeptBlock(noonMs + 4 * hour, noonMs + 6 * hour, secondLost.item.id, 0))
        val successors =
            Successors { _, _, gone ->
                val all = mapOf(lost.item.id to horrorFilm.item.id, secondLost.item.id to kidsComedy.item.id)
                all.filterKeys { it in gone }
            }
        val rig = lostRig(successors, extra = extra)
        rig.library.seed(secondLost)
        rig.gone(secondLost.item.id)

        assertEquals(2, rig.lost(setOf(libA)))

        val fixes = rig.record().fixes
        assertEquals(2, fixes.size)
        for (fix in fixes) {
            val ids = fix.pieces.mapNotNull { it.itemId }
            assertTrue(horrorFilm.item.id !in ids, "the channel's rules refuse the horror film")
            assertTrue(kidsComedy.item.id !in ids, "the grown-up hold refuses the kids' film")
            assertTrue(ids.isNotEmpty(), "it is filled instead")
        }
    }

    @Test
    fun repairLost_matchKeepsSlotAndOffset() {
        val match = mapOf(lost.item.id to admittedLong.item.id)
        val successors = Successors { _, _, gone -> match.filterKeys { it in gone } }
        val rig = lostRig(successors, offset = 7 * minute)

        assertEquals(1, rig.lost(setOf(libA)))

        val fix = rig.record().fixes.single()
        assertTrue(fix.isOfTheLostBlock())
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotEnd, admittedLong.item.id, 7 * minute)), fix.pieces)
    }

    @Test
    fun repairLost_sameGeneration_doesNothing() {
        val rig = lostRig()
        assertEquals(1, rig.lost(setOf(libA)))
        val record = rig.record()
        assertEquals(runBlocking { rig.library.contentGeneration(ACCOUNT) }, record.lostCheckedGeneration)
        val reads = rig.library.currentIdsCalls

        assertEquals(0, rig.lost(setOf(libA)))

        assertEquals(record, rig.record())
        assertEquals(reads, rig.library.currentIdsCalls, "nothing is read again")
    }

    private companion object {
        val LIB_B_ID: CanonicalId = CanonicalId.parse("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0")
    }
}
