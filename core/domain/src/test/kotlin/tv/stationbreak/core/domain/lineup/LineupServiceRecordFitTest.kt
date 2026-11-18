// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.RepairConfig
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.fixOf
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.LineupLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A fix of the repair record, the [n]th day out from [nowMs]; one that covers now if [coversNow]. */
internal fun fixAt(
    nowMs: Long,
    n: Long,
    coversNow: Boolean = false,
) = fixOf(
    n = n,
    dayEndMs = nowMs + n * 86_400_000L,
    blockStartMs = if (coversNow) nowMs - 600_000L else nowMs + n * 86_400_000L - 7_200_000L,
    gone = 100 + n.toInt(),
)

/**
 * Every lineup save fits the record (spec 26): a save trims the repair record to the file's room, and what is published
 * is the record that was saved, so memory equals disk. Split from [LineupServiceRollTest] (#648).
 */
class LineupServiceRecordFitTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private var now = NOON

    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private val nowMs get() = now.toEpochMilliseconds()

    private val pastDueStepMs = 7_200_000L

    private fun pastDue() {
        now = Instant.fromEpochMilliseconds(nowMs + pastDueStepMs)
    }

    private fun fixAt(n: Long) = fixAt(nowMs, n)

    private fun serviceWith(config: RepairConfig = RepairConfig()) =
        LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog, repairConfig = config))

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    // ---- Every lineup save fits the record (memory equals disk) ----

    // A refreshed lineup stored with [record] (and [meta] if given), as a file of version 4.
    private fun seed(
        record: TodayRepairs,
        meta: RefreshMeta? = null,
    ): LineupCodec.Decoded.Loaded {
        runBlocking { serviceWith().refresh(ACCOUNT, LIBRARIES) }
        val base = stored()
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(base.lineup, meta ?: base.meta, record)) }
        return stored()
    }

    private fun sizeOf(
        held: LineupCodec.Decoded.Loaded,
        record: TodayRepairs,
    ) = LineupCodec.encode(held.lineup, held.meta, record).size

    // The config under which [record] is the most the file may hold.
    private fun allowing(
        held: LineupCodec.Decoded.Loaded,
        record: TodayRepairs,
    ) = RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - sizeOf(held, record))

    // The file a refresh leaves holding [record]: the lineup and meta that refresh encodes, so a cap set from them is
    // the cap the next refresh sees (#648).
    private fun afterARefresh(record: TodayRepairs): LineupCodec.Decoded.Loaded {
        seed(record)
        runBlocking { serviceWith().refresh(ACCOUNT, LIBRARIES) }
        return stored()
    }

    private fun agree(service: LineupService): TodayRepairs {
        val published = service.repairs(ACCOUNT).value
        assertEquals(published, stored().repairs, "memory equals disk")
        assertEquals(published, checkNotNull(service.lineup(ACCOUNT).value).repairs)
        return published
    }

    @Test
    fun aCollectionPass_trimsTheRecord_andPublishesWhatItSaved() =
        runBlocking<Unit> {
            val fixes = (1L..3L).map { fixAt(it) }
            val record = TodayRepairs(fixes = fixes)
            val seeded = seed(record, RefreshMeta(plannedGeneration = 1L, collectionPassActedOn = 5L))
            val service = serviceWith(allowing(seeded, record))
            service.loaded(ACCOUNT)
            service.notePass(ACCOUNT, Instant.fromEpochMilliseconds(1_700_000_000_000L))
            assertEquals(1_700_000_000_000L, checkNotNull(service.lineup(ACCOUNT).value).meta.collectionPassActedOn)
            val kept = agree(service)
            assertTrue(kept.fixes.size in 1..2, "the oldest fix went: ${kept.fixes.size}")
            assertEquals(fixes.takeLast(kept.fixes.size), kept.fixes)
        }

    @Test
    fun aRefresh_dropsSelectionRepairsToo_whenTheFixesAreNotEnough() =
        runBlocking<Unit> {
            val early = repairOf(n = 1L, atMs = nowMs + 60_000L, dayEndMs = nowMs + 4 * 3_600_000L)
            val lateEnd = nowMs + 6 * 3_600_000L
            val late = repairOf(channel = "starter:other", n = 2L, atMs = nowMs + 120_000L, dayEndMs = lateEnd)
            val record = TodayRepairs(listOf(late, early), listOf(fixAt(2L)))
            val seeded = afterARefresh(record)
            val lateOnly = sizeOf(seeded, TodayRepairs(listOf(late)))
            val both = sizeOf(seeded, TodayRepairs(listOf(late, early)))
            val config = RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - (lateOnly + both) / 2)
            val trims = mutableListOf<Triple<Int, Int, Int>>()
            val note = { a: Int, b: Int, c: Int -> trims += Triple(a, b, c) }
            val seams = LineupSeams(TestCatalog, repairConfig = config, trimmed = note)
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            service.refresh(ACCOUNT, LIBRARIES)
            val kept = agree(service)
            assertEquals(emptyList(), kept.fixes)
            assertEquals(listOf(late), kept.selection, "the oldest atMs went")
            assertEquals(listOf(Triple(1, 0, 1)), trims)
        }

    @Test
    fun aRoll_trimsWhatIsLeft_andPublishesWhatItSaved() =
        runBlocking<Unit> {
            val soon = nowMs + 7_200_000L
            val ending = fixOf(n = 1L, dayEndMs = soon, blockStartMs = nowMs)
            val rest = (2L..4L).map { fixAt(it) }
            val record = TodayRepairs(fixes = listOf(ending) + rest)
            val seeded = seed(record)
            // The cap is set from what the roll encodes: the lineup as it will be rolled, not as it was seeded (#648).
            val kept = TodayRepairs(fixes = rest.takeLast(2))
            val rolledSize = LineupCodec.encode(seeded.lineup.rolled(nowMs + pastDueStepMs), seeded.meta, kept).size
            val service = serviceWith(RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - rolledSize))
            val held = checkNotNull(service.loaded(ACCOUNT))
            pastDue()
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(rest.takeLast(2), agree(service).fixes, "the ended day went, then the oldest fix")
        }

    @Test
    fun aFailedSave_keepsTheRecordUntrimmed_andReportsNothingTrimmed() =
        runBlocking<Unit> {
            val record = TodayRepairs(fixes = (1L..3L).map { fixAt(it) })
            val seeded = afterARefresh(record)
            // Room for one fix only, so a save that went through would have to trim the record.
            val config = allowing(seeded, TodayRepairs(fixes = listOf(fixAt(3L))))
            assertTrue(sizeOf(seeded, record) > LineupLimits.MAX_FILE_BYTES - config.fileMargin, "over the cap")
            val trims = mutableListOf<Triple<Int, Int, Int>>()
            val seams =
                LineupSeams(TestCatalog, repairConfig = config, trimmed = { a, b, c -> trims += Triple(a, b, c) })
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            service.loaded(ACCOUNT)
            val file = checkNotNull(store.inner.peek(ACCOUNT)).toList()

            store.inner.failWrites = true
            service.refresh(ACCOUNT, LIBRARIES)
            store.inner.failWrites = false

            assertEquals(file, checkNotNull(store.inner.peek(ACCOUNT)).toList(), "the file is as it was")
            assertEquals(record, service.repairs(ACCOUNT).value, "the record was not trimmed in memory either")
            assertEquals(emptyList(), trims, "nothing was dropped, so nothing is reported")
        }
}
