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
        assertEquals(generation, rig.record().lostCheckedGeneration)

        assertNotNull(rig.change(before = setOf(libA), after = setOf(libA)))

        assertTrue(goneId !in rig.repairOf(laughs.id).tailIds(), "a fresh tail lays out nothing with no row")
        assertNull(rig.record().lostCheckedGeneration, "the new tails are checked again")
        val reads = rig.library.currentIdsCalls
        rig.lost(setOf(libA))
        assertTrue(rig.library.currentIdsCalls > reads, "the next check runs at the same generation")
        assertEquals(generation, rig.record().lostCheckedGeneration)
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
        assertNull(rig.record().lostCheckedGeneration, "the generation is not stamped")
    }

    @Test
    fun repairLost_noDayOnTheAir_writesNothing() {
        val rig = Rig(lineupOf(laughs), comedies, emptySet())

        assertEquals(0, rig.lost(setOf(libA)))

        assertNull(rig.record().lostCheckedGeneration)
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
        val lineup =
            lineupOf(
                starter("laughs", 110, ChannelState.On, programmed("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, programmed("Whodunit", Source.Genre("Crime"))),
                leftovers("odds-and-ends", 199, null),
            )
        val items =
            comedies.take(4) + crimes.take(4) + film(180, "Documentary") + film(1300, "Comedy", "Crime", library = libB)
        val warm = Rig(lineup, items, setOf(libA), disk = disk)
        warm.days()
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
        val curatedLaughs = programmed("Laughs", Source.Genre("Comedy"))
        val lineup = lineupOf(starter("laughs", 110, ChannelState.On, curatedLaughs))

        // One film of the box set airs today; the change adds the other two, and films outside it.
        fun RuleItem.of(year: Int) = copy(item = item.copy(productionYear = year))
        val todays = film(1702, "Comedy").of(1990)
        val boxSet =
            listOf(
                film(1700, "Comedy", library = libB).of(1992),
                film(1701, "Comedy", library = libB).of(1991),
            )
        val others = (0 until 6).map { film(1710 + it, "Comedy", library = libB).of(1980 + it) }
        val rig = Rig(lineup, comedies.take(2) + todays + boxSet + others, setOf(libA))
        val members = ids(boxSet) + todays.item.id
        val collection = RuleCollection(id(1799), members, emptySet(), emptySet(), emptySet(), "Synthetic Collection")
        rig.library.delegate.seedCollection(ACCOUNT, collection)
        val first = rig.day(laughs.id)
        val lineupDays = LineupDays(TestCatalog)
        val scheduled = lineupDays.scheduled(lineup, atMs, null)
        val snapshot =
            runBlocking {
                val cutoff = Instant.fromEpochMilliseconds(scheduled.single().cutoffMs)
                rig.library.delegate.ruleSnapshot(ACCOUNT, setOf(libA, libB), cutoff)
            }
        val after = lineupDays.days(lineup, scheduled, mapOf(scheduled.single().cutoffMs to snapshot)).single()

        rig.change(before = setOf(libA), after = setOf(libA, libB))

        // The first change: the day's own films and weights, and the new selection's for the added titles.
        val added = ids(boxSet + others)
        val pool1 = (first.pool + after.pool.filter { it.id in added }).sortedBy { it.id.value }
        val films1 = first.films.entries.toMap() + after.films.entries.filter { it.first in added }
        val weights1 = first.weights.entries.toMap() + after.weights.entries.filter { it.first in added }
        val todaysFilms = first.films.entries.map { it.first }
        assertEquals(listOf(todays.item.id), todaysFilms, "today's day groups its box-set film")
        assertEquals(members, films1.keys, "the whole box set is grouped")
        val repair1 = rig.repairOf(laughs.id)
        assertEquals(laid(first, pool1, films1, weights1, repair1.pinEndMs), repair1.tail())

        // A second change: the record's unaired tail, laid with the new selection's films and weights for all.
        val second = repair1.pinEndMs
        val day2 = rig.day(laughs.id, second)
        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA, libB), at = second))
        val repair2 = rig.repairOf(laughs.id)
        val airedRecord = repair1.blocks.filter { it.startMs < second }.mapNotNullTo(HashSet()) { it.itemId }
        val ids2 = repair1.tailIds() - airedRecord
        val pool2 = snapshot.items.map { it.item }.filter { it.id in ids2 }
        val films2 =
            after.films.entries
                .filter { it.first in ids2 }
                .toMap()
        assertTrue(films2.isNotEmpty(), "the box set is still in the tail pool")
        val weights2 = after.weights.entries.filter { it.first in pool2.mapTo(HashSet()) { item -> item.groupId } }
        assertEquals(laid(day2, pool2, films2, weights2.toMap(), repair2.pinEndMs), repair2.tail())
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
        val tail = layoutTail(day.settings, pool, day.n, sides.first, sides.second, fromMs, aired)
        return tail.map { kept(it) }
    }
}
