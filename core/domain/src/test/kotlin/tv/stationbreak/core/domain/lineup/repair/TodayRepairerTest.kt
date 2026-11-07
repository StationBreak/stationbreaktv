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

/**
 * The repair engine (spec 26, Task 2.5), a change of the selection: it pins every channel's programme on air and
 * records a re-planned tail.
 */
internal class TodayRepairerTest : RepairTestBase() {
    @Test
    fun selectionChanged_pinsEveryChannelsCurrentProgramme_recordsTail() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA, libB))
        val before = rig.days()
        val pins = before.associate { it.channel.id to it.blockAt(atMs) }

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        for (day in before) {
            val repair = rig.repairOf(day.channel.id)
            val pin = pins.getValue(day.channel.id)
            assertEquals(day.n, repair.n)
            assertEquals(day.epochEndMs, repair.dayEndMs)
            assertEquals(atMs, repair.atMs)
            assertEquals(1, repair.seq, "the first change of a TV with no seq saved")
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

    @Test
    fun selectionChanged_noTitleChangesChannel() {
        val westerns = (0 until 4).map { film(140 + it, "Western") }
        val horror = (0 until 3).map { film(160 + it, "Horror") }
        val custom = custom("custom-1", 600, rules("Mine", Source.Genre("Horror")))
        val lineup =
            lineupOf(
                starter("laughs", 110, ChannelState.On, programmed("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, programmed("Whodunit", Source.Genre("Crime"))),
                starter("westerns", 220, ChannelState.On, rules("Westerns", Source.Genre("Western"))),
                custom,
                leftovers("odds-and-ends", 199, null),
            )
        val docs = (0 until 3).map { film(180 + it, "Documentary") }
        // A crime series on the air today, and new episodes of it that a comedy channel would admit too.
        val series = id(1650)
        val airing = (1..3).map { episode(1650 + it, series, it, libA, listOf("Crime")) }
        val newEpisodes = (4..5).map { episode(1650 + it, series, it, libC, listOf("Comedy", "Crime")) }
        val all = comedies + crimes + westerns + horror + docs + comediesB + crimesB + airing + newEpisodes
        val rig = Rig(lineup, all, setOf(libA, libB))
        val before = rig.days().associate { it.channel.id to it.pool.mapTo(HashSet()) { item -> item.groupId } }

        rig.change(before = setOf(libA, libB), after = setOf(libA, libC))

        for ((channel, titles) in before) {
            val tail = rig.repairOf(channel).tail().mapNotNull { it.itemId }
            val tailTitles = tail.mapTo(HashSet()) { item -> if (item in ids(airing + newEpisodes)) series else item }
            assertTrue(tail.isNotEmpty())
            assertTrue(titles.containsAll(tailTitles), "every title of $channel's tail was its own already")
        }
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
    fun secondChangeSameDay_poolIsTailPoolAndAdmittedNow_lessAired_minusRemoved_plusAdded() {
        val mine = custom("custom-1", 600, rules("Mine", Source.Genre("Comedy")))
        val comediesC = listOf(film(500, "Comedy", library = libC))
        val comediesD = listOf(film(520, "Comedy", library = libD))
        val rig = Rig(lineupOf(mine), comedies.take(4) + comediesB + comediesC + comediesD, setOf(libA, libB))
        rig.change(before = setOf(libA, libB), after = setOf(libA, libB, libC))
        val first = rig.repairOf(mine.id)
        val second = atMs + 4 * hour
        val aired = first.blocks.filter { it.startMs < second }.mapNotNullTo(HashSet()) { it.itemId }

        assertNotNull(rig.change(before = setOf(libA, libB, libC), after = setOf(libA, libC, libD), at = second))

        val repair = rig.repairOf(mine.id)
        assertEquals(second, repair.atMs, "the latest repair of the day replaces the first")
        val tail = repair.tailIds()
        assertTrue(tail.none { it in ids(comediesB) }, "the removed library is gone from the tail")
        assertTrue(comediesD.single().item.id in tail, "the added title is in it")
        val admittedNow = ids(comedies.take(4) + comediesB + comediesC)
        val base = admittedNow - aired - ids(comediesB)
        assertTrue((base + ids(comediesD)).containsAll(tail), "the base: what the channel admits now, less aired")
    }

    @Test
    fun secondChangeSameDay_anItemOfTheDayPoolTheFirstTailLeftOut_isThereForTheSecond() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA))
        val end = rig.day(laughs.id).epochEndMs
        // A late first change that adds a library: its tail is all the added library's.
        rig.change(before = setOf(libA), after = setOf(libA, libB), at = end - 4 * hour - 10 * minute)
        val first = rig.repairOf(laughs.id)
        assertTrue(first.tailIds().isNotEmpty() && ids(comediesB).containsAll(first.tailIds()), "the set-up")
        val leftOut = ids(comedies) - first.blocks.mapNotNullTo(HashSet()) { it.itemId }
        val secondAt = first.pinEndMs + minute
        val aired = first.blocks.filter { it.startMs < secondAt }.mapNotNullTo(HashSet()) { it.itemId }

        // The second change takes that library away again: the first record's tail pool has nothing left to lay.
        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA), at = secondAt))

        val second = rig.repairOf(laughs.id)
        assertEquals(2L, second.seq)
        val tail = second.tailIds()
        assertTrue(tail.none { it in aired }, "what the record aired is left out: $tail")
        assertTrue(ids(comedies).containsAll(tail), "nothing of the removed library: $tail")
        assertTrue(tail.any { it in leftOut }, "a film of the day pool the first record left out airs: $tail")
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
}
