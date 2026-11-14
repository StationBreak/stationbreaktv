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
 * The repair engine's guards (spec 26, Task 2.5 fix round 1): it writes nothing stale, lays out nothing gone, never
 * homes again what airs today, and records the same repairs whatever the cache holds.
 */
internal class TodayRepairerGuardsTest : RepairTestBase() {
    @Test
    fun selectionChanged_afterARowWent_tailLeavesItOut_andTheNextCheckRuns() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        val day = rig.day(laughs.id)
        val pinEnd = day.blockAt(atMs).endMs
        val goneId =
            day.day.blocks
                .filter { it.startMs >= pinEnd }
                .firstNotNullOf { (it.content as? ScheduleContent.Program)?.itemId }
        rig.gone(goneId)
        assertTrue(rig.lost(setOf(libA)) > 0)
        val generation = runBlocking { rig.library.contentGeneration(ACCOUNT) }
        assertEquals(generation, rig.record().lostCheck?.generation)

        assertNotNull(rig.change(before = setOf(libA), after = setOf(libA)))

        assertTrue(goneId !in rig.repairOf(laughs.id).tailIds(), "a fresh tail lays out nothing with no row")
        assertNull(rig.record().lostCheck, "the new tails are checked again")
        val reads = rig.library.currentIdsCalls
        rig.lost(setOf(libA))
        assertTrue(rig.library.currentIdsCalls > reads, "the next check runs at the same generation")
        assertEquals(generation, rig.record().lostCheck?.generation)
    }

    @Test
    fun repairLost_lineupMovesWhileItRuns_writesNothing() {
        lateinit var rig: Rig
        var revisions = 0L to 0L
        val moving =
            Successors { _, _, _ ->
                val before = assertNotNull(rig.service.loaded(ACCOUNT)).revision
                assertIs<Saved.Ok<*>>(rig.service.edit(ACCOUNT, LineupEdit.SetNumber(laughs.id, 150)))
                revisions = before to assertNotNull(rig.service.loaded(ACCOUNT)).revision
                emptyMap()
            }
        rig = lostRig(moving)

        assertEquals(0, rig.lost(setOf(libA)))

        assertTrue(revisions.second != revisions.first, "the lineup moved while the repair ran")
        assertEquals(emptyList(), rig.record().fixes, "no fix written")
        assertNull(rig.record().lostCheck, "the generation is not stamped")
    }

    @Test
    fun repairLost_noDayOnTheAir_writesNothing() {
        val rig = Rig(lineupOf(laughs), comedies, emptySet())

        // Nothing chosen, so nothing playable either (a playable library is always a chosen one).
        assertEquals(0, rig.lost(emptySet()))

        assertNull(rig.record().lostCheck)
    }

    @Test
    fun selectionChanged_saveFails_returnsNull_changesNothing() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        rig.store.inner.failWrites = true

        assertNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        rig.store.inner.failWrites = false
        assertEquals(emptyList(), rig.record().selection)
    }

    @Test
    fun selectionChanged_lineupMovesMidPlan_returnsNull_changesNothing() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        rig.days()
        var edited = false
        rig.library.afterSnapshot = {
            if (!edited) {
                edited = true
                val renumber = LineupEdit.SetNumber(whodunit.id, 250)
                runBlocking { assertIs<Saved.Ok<*>>(rig.service.edit(ACCOUNT, renumber)) }
            }
        }

        val revision = assertNotNull(runBlocking { rig.service.loaded(ACCOUNT) }).revision

        assertNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        assertTrue(edited)
        assertTrue(assertNotNull(runBlocking { rig.service.loaded(ACCOUNT) }).revision != revision, "it moved mid-plan")
        assertEquals(emptyList(), rig.record().selection)
    }

    @Test
    fun tailPoolOf_aTitleAiringElsewhereToday_isNeverHomedAgain() {
        val curatedLaughs = programmed("Laughs", Source.Genre("Comedy"))
        val curatedWhodunit = programmed("Whodunit", Source.Genre("Crime"))
        val lineup =
            lineupOf(
                starter("laughs", 110, ChannelState.On, curatedLaughs),
                starter("whodunit", 210, ChannelState.On, curatedWhodunit),
            )
        val crime = film(1100, "Crime")
        val items = comedies.take(2) + crime
        val lineupDays = LineupDays(TestCatalog)
        val s = lineupDays.scheduled(lineup, atMs, null).single { it.channel.id == whodunit.id }
        val snapshot =
            runBlocking {
                val lib = CountingLibrary().also { it.seed(items) }
                lib.delegate.ruleSnapshot(ACCOUNT, setOf(libA), Instant.fromEpochMilliseconds(s.cutoffMs))
            }
        val homes =
            Curation.homes(
                snapshot,
                listOf(
                    curationChannel("laughs", curatedLaughs, snapshot),
                    curationChannel("whodunit", curatedWhodunit, snapshot),
                ),
            )
        assertEquals(whodunit.id, homes.homeOf(crime.item.id), "whodunit is the title's home")
        val title = setOf(crime.item.groupId)

        fun pool(today: Set<CanonicalId>) =
            lineupDays.tailPoolOf(s, emptyList(), emptySet(), listOf(crime.item), homes, today, own = emptySet())

        assertEquals(emptyList(), pool(today = title), "airing on another channel today: never homed again")
        assertEquals(listOf(crime.item), pool(today = emptySet()), "a new title goes to its home")
    }

    @Test
    fun sameChange_warmOrRestoredFromDisk_recordsTheSameRepairs() {
        val disk = MemoryDayStore()
        // The box set is a source of laughs, so its films are anchors (weight 3) beside homes (2), and grouped.
        val laughsRules = programmed("Laughs", Source.Genre("Comedy"), Source.Collection(BOX_SET))
        val lineup =
            lineupOf(
                starter("laughs", 110, ChannelState.On, laughsRules),
                starter("whodunit", 210, ChannelState.On, programmed("Whodunit", Source.Genre("Crime"))),
                leftovers("odds-and-ends", 199, null),
            )
        val boxSet =
            listOf(
                film(1702, "Comedy").of(1990),
                film(1700, "Comedy", library = libB).of(1992),
                film(1701, "Comedy", library = libB).of(1991),
            )
        val others = (0 until 6).map { film(1710 + it, "Comedy", library = libB).of(1980 + it) }
        val both = film(1300, "Comedy", "Crime", library = libB)
        val items = comedies.take(4) + crimes.take(4) + film(180, "Documentary") + both + boxSet + others
        val warm = Rig(lineup, items, setOf(libA), disk = disk)
        warm.library.delegate.seedCollection(ACCOUNT, boxSetOf(ids(boxSet)))
        val today = warm.day(laughs.id)
        assertTrue(today.films.entries.isNotEmpty(), "today's day groups its box-set film")
        assertEquals(setOf(2, 3), today.weights.entries.mapTo(HashSet()) { it.second }, "anchors beside homes")
        runBlocking { warm.source.awaitSaves() }
        val restored = Rig(lineup, emptyList(), setOf(libA), disk = disk, sharedLibrary = warm.library)
        restored.days()
        assertEquals("disk", restored.builds.single().snapshot, "the second TV's days come from disk")

        warm.change(before = setOf(libA), after = setOf(libA, libB))
        restored.change(before = setOf(libA), after = setOf(libA, libB))

        assertEquals(warm.record().selection, restored.record().selection)
    }

    @Test
    fun addedBoxSet_tailIsLaidWithTheMergedFilmsAndWeights_firstAndSecondChange() {
        val box = BoxSetDay()
        val first = box.rig.day(laughs.id)
        val after = box.builtOver(setOf(libA, libB))

        box.rig.change(before = setOf(libA), after = setOf(libA, libB))

        // The first change: the day's own films and weights, and the new selection's for the added titles.
        val added = ids(box.boxSet + box.others)
        val pool1 = (first.pool + after.pool.filter { it.id in added }).sortedBy { it.id.value }
        val films1 = first.films.entries.toMap() + after.films.entries.filter { it.first in added }
        val weights1 = first.weights.entries.toMap() + after.weights.entries.filter { it.first in added }
        assertEquals(listOf(box.todays.item.id), first.films.entries.map { it.first }, "today groups its box-set film")
        assertEquals(box.members, films1.keys, "the whole box set is grouped")
        val repair1 = box.rig.repairOf(laughs.id)
        assertEquals(laid(first, pool1, films1, weights1, repair1.pinEndMs), repair1.tail())

        // A second change that adds a library: the record's tail pool and what laughs admits now (every comedy of
        // libraries A and B), less what the record airs before it, plus the added film, laid with the new selection's
        // films and weights for every title of the pool (anchors at 3, homes at 2).
        val second = repair1.pinEndMs
        val day2 = box.rig.day(laughs.id, second)
        val all = setOf(libA, libB, libC)
        assertNotNull(box.rig.change(before = setOf(libA, libB), after = all, at = second))
        val repair2 = box.rig.repairOf(laughs.id)
        val after2 = box.builtOver(all)
        val airedRecord = repair1.blocks.filter { it.startMs < second }.mapNotNullTo(HashSet()) { it.programId() }
        val admittedNow = ids(comedies.take(2) + box.todays + box.boxSet + box.others)
        val ids2 = repair1.tailIds() + admittedNow - airedRecord + box.extra.item.id
        val pool2 =
            box
                .snapshotOf(all)
                .items
                .map { it.item }
                .filter { it.id in ids2 }
        val titles2 = pool2.mapTo(HashSet()) { it.groupId }
        val films2 =
            after2.films.entries
                .filter { it.first in ids2 }
                .toMap()
        val weights2 =
            after2.weights.entries
                .filter { it.first in titles2 }
                .toMap()
        assertTrue(films2.isNotEmpty(), "the box set is still in the tail pool")
        assertTrue(weights2.values.toSet().containsAll(setOf(2, 3)), "anchors and homes, not all the default")
        val expected2 = laid(day2, pool2, films2, weights2, repair2.pinEndMs)
        assertTrue(expected2 != laid(day2, pool2, films2, emptyMap(), repair2.pinEndMs), "the weights matter")
        assertEquals(expected2, repair2.tail())
    }

    @Test
    fun unrepairedDay_aTitleTheNewSelectionWouldAnchor_keepsTodaysWeight() {
        // Series S airs on laughs today as a home (weight 2). The change adds an episode that is in a collection
        // laughs names, so the new selection's build would make S an anchor (weight 3): today's weight stands.
        val picks = id(1898)
        val rules = programmed("Laughs", Source.Genre("Comedy"), Source.Collection(picks))
        val lineup = lineupOf(starter("laughs", 110, ChannelState.On, rules))
        val series = id(1800)
        val todays = (1..4).map { episode(1800 + it, series, it, libA, listOf("Comedy")) }
        val added = episode(1805, series, 5, libB, listOf("Comedy"))
        val rig = Rig(lineup, comedies + todays + added, setOf(libA))
        rig.library.delegate.seedCollection(
            ACCOUNT,
            RuleCollection(picks, emptySet(), setOf(added.item.id), emptySet(), emptySet(), "Synthetic Picks"),
        )
        val first = rig.day(laughs.id)
        val after = laughsBuiltOver(rig, lineup, setOf(libA, libB))
        assertEquals(2, first.weights[series], "a home today")
        assertEquals(3, after.weights[series], "an anchor in the new selection's build")

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        val repair = rig.repairOf(laughs.id)
        val pool = (first.pool + added.item).sortedBy { it.id.value }
        val titles = pool.mapTo(HashSet()) { it.groupId }
        val weights =
            first.weights.entries
                .filter { it.first in titles }
                .toMap()
        val films = first.films.entries.toMap()
        val expected = laid(first, pool, films, weights, repair.pinEndMs)
        assertTrue(expected != laid(first, pool, films, weights + (series to 3), repair.pinEndMs), "S's weight matters")
        assertEquals(expected, repair.tail())
    }

    private fun boxSetOf(members: Set<CanonicalId>) =
        RuleCollection(BOX_SET, members, emptySet(), emptySet(), emptySet(), "Synthetic Collection")

    private fun RuleItem.of(year: Int) = copy(item = item.copy(productionYear = year))

    // [rig]'s day of laughs as the build makes it over [libraries] at the repair's cut-off.
    private fun laughsBuiltOver(
        rig: Rig,
        lineup: Lineup,
        libraries: Set<ItemId>,
    ): ChannelDay {
        val lineupDays = LineupDays(TestCatalog)
        val scheduled = lineupDays.scheduled(lineup, atMs, null)
        val cutoff = scheduled.single().cutoffMs
        val snapshot =
            runBlocking { rig.library.delegate.ruleSnapshot(ACCOUNT, libraries, Instant.fromEpochMilliseconds(cutoff)) }
        return lineupDays.days(lineup, scheduled, mapOf(cutoff to snapshot)).single { it.channel.id == laughs.id }
    }

    /**
     * Laughs, curated, with a box set it names as a source: one film of it airs today, a change adds the other two
     * and films outside it (library B), and [extra] is one more comedy in library C. Distinct years make the grouping
     * visible in the layout.
     */
    private inner class BoxSetDay {
        val lineup =
            lineupOf(
                starter(
                    "laughs",
                    110,
                    ChannelState.On,
                    programmed("Laughs", Source.Genre("Comedy"), Source.Collection(BOX_SET)),
                ),
            )
        val todays = film(1702, "Comedy").of(1990)
        val boxSet =
            listOf(
                film(1700, "Comedy", library = libB).of(1992),
                film(1701, "Comedy", library = libB).of(1991),
            )
        val others = (0 until 6).map { film(1710 + it, "Comedy", library = libB).of(1980 + it) }
        val extra = film(1720, "Comedy", library = libC).of(1970)
        val members = ids(boxSet) + todays.item.id
        val rig = Rig(lineup, comedies.take(2) + todays + boxSet + others + extra, setOf(libA))

        init {
            rig.library.delegate.seedCollection(ACCOUNT, boxSetOf(members))
        }

        fun snapshotOf(libraries: Set<ItemId>): RuleSnapshot {
            val cutoff = LineupDays(TestCatalog).scheduled(lineup, atMs, null).single().cutoffMs
            return runBlocking {
                rig.library.delegate.ruleSnapshot(ACCOUNT, libraries, Instant.fromEpochMilliseconds(cutoff))
            }
        }

        fun builtOver(libraries: Set<ItemId>): ChannelDay = laughsBuiltOver(rig, lineup, libraries)
    }

    // The scheduler's own tail of [day] from [fromMs] over [pool], with what aired before it left out, as kept.
    private fun laid(
        day: ChannelDay,
        pool: List<ScheduleItem>,
        films: Map<CanonicalId, CanonicalId>,
        weights: Map<CanonicalId, Int>,
        fromMs: Long,
    ): List<KeptBlock> {
        val aired = programmesBefore(day, fromMs)
        val sides = FilmCollections.of(films) to TitleWeights.of(weights)
        val before = pastAirings(day, fromMs)
        val tail = layoutTail(day.settings, pool, day.n, sides.first, sides.second, fromMs, aired, before)
        return tail.map { kept(it) }
    }

    private companion object {
        val BOX_SET: CanonicalId = id(1799)
    }
}
