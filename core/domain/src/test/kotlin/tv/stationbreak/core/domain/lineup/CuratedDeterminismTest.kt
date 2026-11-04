// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Curated days are a pure function of the lineup and the library (ADR-0002), and survive the disk unchanged. */
class CuratedDeterminismTest {
    private val lib = CuratedLibrary

    private val noon = NOON.toEpochMilliseconds()

    // Today under a basis (whodunit is leaving, westerns came on mid-day), so every path of the curation is in play.
    private val lineup: Lineup =
        lib.lineup().let { base ->
            base.copy(
                channels =
                    base.channels.map { if (it.id == lib.whodunitId) it.copy(leavesAtMs = boundaryMs) else it },
                curationBasis = CurationBasis(boundaryMs, setOf(lib.laughsId, lib.whodunitId), true),
            )
        }

    @Test
    fun twoBuildsOfOneLineupGiveIdenticalDays() {
        val basis = checkNotNull(lineup.curationBasis)
        val reordered =
            lineup.copy(
                channels = lineup.channels.reversed(),
                curationBasis = basis.copy(on = basis.on.reversed().toSet()),
            )

        for (atMs in listOf(noon, noon + lib.DAY_MS)) {
            val first = LineupDays(TestCatalog).curatedDays(lineup, atMs)
            val second = LineupDays(TestCatalog).curatedDays(reordered, atMs)
            assertEquals(first.map { it.channel.id }, second.map { it.channel.id })
            assertEquals(first.map { it.aired() }, second.map { it.aired() })
        }
    }

    @Test
    fun persistedCuratedDaysRestoreIdentically() {
        val days = LineupDays(TestCatalog)
        val store = MemoryDayStore()
        val persisted = PersistedDays(store, "app-1", {}, PersistedDays.HOLDS)
        val scheduled = days.scheduled(lineup, noon, null)
        val key =
            persisted.keyOf(ACCOUNT, LIBRARIES, 1L, lineup.utcOffsetMinutes, days.inputsOf(lineup, null), 1, scheduled)
        val fresh = days.curatedDays(lineup, noon)
        assertTrue(fresh.any { !it.weights.isEmpty }, "a day has weights")
        val laughs = fresh.dayOf(lib.laughsId.value)
        assertTrue(laughs.ids().count { it in lib.ids(lib.showAEpisodes) } < lib.EPISODES, "trimmed")

        assertTrue(runBlocking { persisted.save(ACCOUNT, key, fresh) })
        val restored = assertNotNull(runBlocking { persisted.load(ACCOUNT, key, scheduled, days::restore) })

        assertEquals(fresh.map { it.aired() }, restored.map { it.aired() })
        assertEquals(describe(fresh), describe(restored))
    }

    @Test
    fun aLeavingStarterIsScheduledOnlyUntilItsBoundary() {
        val days = LineupDays(TestCatalog)
        val today = days.scheduled(lineup, noon, null).map { it.channel.id }
        val next = days.scheduled(lineup, noon + lib.DAY_MS, null).map { it.channel.id }
        assertTrue(lib.whodunitId in today)
        assertTrue(lib.whodunitId !in next)
        assertTrue(lineup.channels.first { it.id == lib.whodunitId }.state == ChannelState.On)
    }
}
