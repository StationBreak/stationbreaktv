// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The record's `lastSeq` lives only as long as a selection repair does (spec 26, ADR-0025 §6). */
class RepairSeqRollTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private var now = NOON
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun service() = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog))

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    @Test
    fun whenTheLastSelectionRepairRollsOff_theFileIsVersion3() =
        runBlocking<Unit> {
            val nowMs = NOON.toEpochMilliseconds()
            val soon = nowMs + 7_200_000L
            service().refresh(ACCOUNT, LIBRARIES)
            val record = TodayRepairs(selection = listOf(repairOf(dayEndMs = soon, seq = 5L)), lastSeq = 6L)
            assertTrue(service().recordRepairs(ACCOUNT) { record })
            assertEquals(4, stored().fileVersion)
            assertEquals(6L, stored().repairs.lastSeq, "stored while a selection repair is held")

            now = Instant.fromEpochMilliseconds(soon + 1)
            val restarted = service()
            restarted.rollSoon(ACCOUNT, checkNotNull(restarted.loaded(ACCOUNT)))
            restarted.awaitRolls()

            assertEquals(3, stored().fileVersion)
            assertEquals(TodayRepairs.NONE, stored().repairs)
            assertEquals(TodayRepairs.NONE, restarted.repairs(ACCOUNT).value)
        }
}
