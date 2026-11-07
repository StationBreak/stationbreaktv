// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.ChannelRepair
import tv.stationbreak.core.domain.lineup.repair.RepairConfig
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.model.LineupLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A save of another kind near the file's cap trims the selection repairs a TV does not show first (spec 26, fix round
 * 5): the saved seq reaches the service, read before the lock, and the seq being saved counts as shown.
 */
class LineupServiceTrimOrderTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val nowMs = NOON.toEpochMilliseconds()

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    // A refreshed lineup stored with [record], as a file of version 4.
    private fun seed(record: TodayRepairs): LineupCodec.Decoded.Loaded {
        val plain = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog))
        runBlocking { plain.refresh(ACCOUNT, LIBRARIES) }
        val base = stored()
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(base.lineup, base.meta, record)) }
        return stored()
    }

    private fun sizeOf(
        held: LineupCodec.Decoded.Loaded,
        record: TodayRepairs,
    ) = LineupCodec.encode(held.lineup, held.meta, record).size

    private fun agree(service: LineupService): TodayRepairs {
        val published = service.repairs(ACCOUNT).value
        assertEquals(published, stored().repairs, "memory equals disk")
        return published
    }

    // Two repairs on two days: the shown one (seq 4) is older, the unsaved one (seq 5) newer. Room for one.
    private fun shownAndUnsaved(): Triple<ChannelRepair, ChannelRepair, LineupSeams> {
        val shown = repairOf(n = 1L, atMs = nowMs + 60_000L, dayEndMs = nowMs + 4 * 3_600_000L, seq = 4L)
        val unsavedEnd = nowMs + 6 * 3_600_000L
        val unsaved =
            repairOf(channel = "starter:other", n = 2L, atMs = nowMs + 120_000L, dayEndMs = unsavedEnd, seq = 5L)
        val seeded = seed(TodayRepairs(listOf(unsaved, shown)))
        val one = sizeOf(seeded, TodayRepairs(listOf(shown)))
        val both = sizeOf(seeded, TodayRepairs(listOf(shown, unsaved)))
        val config = RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - (one + both) / 2)
        return Triple(shown, unsaved, LineupSeams(TestCatalog, repairConfig = config))
    }

    @Test
    fun aRefreshNearTheCap_dropsTheUnsavedRepairBeforeTheShownOne_readingTheSavedSeqBeforeTheLock() =
        runBlocking<Unit> {
            val (shown, _, seams) = shownAndUnsaved()
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            var lockedAtRead: Boolean? = null
            service.savedSeqFrom {
                lockedAtRead = service.lockOf(ACCOUNT)?.isLocked
                4L
            }

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(shown), agree(service).selection, "the unsaved one went, though the shown one is older")
            assertEquals(false, lockedAtRead, "the saved seq was read before the lock was taken")
        }

    @Test
    fun aRefreshNearTheCap_whileASeqIsBeingSaved_keepsThatSeqsRepair() =
        runBlocking<Unit> {
            val (_, unsaved, seams) = shownAndUnsaved()
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            service.savedSeqFrom { 4L }
            service.saving(5L)

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(unsaved), agree(service).selection, "the change being saved stays")
        }
}
