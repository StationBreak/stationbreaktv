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

/** The repair engine (spec 26, Task 2.5), lost blocks: a block whose item left the library is matched or filled. */
internal class TodayRepairerLostTest : RepairTestBase() {
    @Test
    fun goneProgrammeOnAir_keepsTheFillItShows_andItsRestStaysUnnamed() {
        val settings = laughsRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val start = Epochs.epochStart(settings, n)
        val end = Epochs.epochStart(settings, n + 1)
        val short = film(710, "Comedy", minutes = 90)
        val blocks =
            listOf(
                KeptBlock(start, noonMs, KeptContent.Slate),
                KeptBlock(noonMs, noonMs + 2 * hour, KeptContent.Program(lost.item.id, 0)),
                KeptBlock(noonMs + 2 * hour, noonMs + 2 * hour + 90 * minute, KeptContent.Program(short.item.id, 0)),
                KeptBlock(noonMs + 2 * hour + 90 * minute, end, KeptContent.Slate),
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
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotEnd, KeptContent.Program(admittedLong.item.id, 0))), fix.pieces)
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
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotStart + hour, KeptContent.Program(shortTail.item.id, 0))), fix.pieces)
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
        val extra = listOf(KeptBlock(noonMs + 4 * hour, noonMs + 6 * hour, KeptContent.Program(secondLost.item.id, 0)))
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
        assertEquals(listOf(KeptBlock(lostSlotStart, lostSlotEnd, KeptContent.Program(admittedLong.item.id, 7 * minute))), fix.pieces)
    }

    @Test
    fun repairLost_sameGeneration_doesNothing() {
        val rig = lostRig()
        assertEquals(1, rig.lost(setOf(libA)))
        val record = rig.record()
        assertEquals(runBlocking { rig.library.contentGeneration(ACCOUNT) }, record.lostCheck?.generation)
        val reads = rig.library.currentIdsCalls

        assertEquals(0, rig.lost(setOf(libA)))

        assertEquals(record, rig.record())
        assertEquals(reads, rig.library.currentIdsCalls, "nothing is read again")
    }
}
