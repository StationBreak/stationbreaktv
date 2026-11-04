// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.Curated.boundaryMs
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Curated days are a pure function of the lineup and the library (ADR-0002), and survive the disk unchanged. */
class CuratedDeterminismTest {
    private val noon = NOON.toEpochMilliseconds()

    // Today under a basis (whodunit is leaving, westerns came on mid-day), so every path of the curation is in play.
    private val lineup: Lineup =
        Curated.lineup().let { base ->
            base.copy(
                channels =
                    base.channels.map { if (it.id == Curated.whodunitId) it.copy(leavesAtMs = boundaryMs) else it },
                curationBasis = CurationBasis(boundaryMs, setOf(Curated.laughsId, Curated.whodunitId), true),
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

        for (atMs in listOf(noon, noon + Curated.DAY_MS)) {
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
        val laughs = fresh.dayOf(Curated.laughsId.value)
        assertTrue(laughs.ids().count { it in Curated.ids(Curated.showAEpisodes) } < Curated.EPISODES, "trimmed")

        assertTrue(runBlocking { persisted.save(ACCOUNT, key, fresh) })
        val restored = assertNotNull(runBlocking { persisted.load(ACCOUNT, key, scheduled, days::restore) })

        assertEquals(fresh.map { it.aired() }, restored.map { it.aired() })
        assertEquals(describe(fresh), describe(restored))
    }

    @Test
    fun aLeavingStarterIsScheduledOnlyUntilItsBoundary() {
        val days = LineupDays(TestCatalog)
        val today = days.scheduled(lineup, noon, null).map { it.channel.id }
        val next = days.scheduled(lineup, noon + Curated.DAY_MS, null).map { it.channel.id }
        assertTrue(Curated.whodunitId in today)
        assertTrue(Curated.whodunitId !in next)
        assertTrue(lineup.channels.first { it.id == Curated.whodunitId }.state == ChannelState.On)
    }
}
