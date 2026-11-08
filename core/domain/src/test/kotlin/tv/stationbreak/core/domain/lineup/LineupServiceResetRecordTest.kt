// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.repair.LostCheck
import tv.stationbreak.core.domain.lineup.repair.RepairConfig
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.fixOf
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `resetRecord` and `floorRecord` (spec 26, Part 4 §2): each reads the stored file itself (never the lineup held in
 * memory) and acts on it under the account's lock, in one call, for every state the file can be in.
 */
class LineupServiceResetRecordTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val events = mutableListOf<LineupEvent>()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private fun newService() = LineupService(store, library, clock, { 0 }, { events += it }, TestCatalog)

    private val record =
        TodayRepairs(
            selection = listOf(repairOf(seq = 4L), repairOf(n = 2L, seq = 6L)),
            fixes = listOf(fixOf(seq = 4L), fixOf(n = 3L)),
            lastSeq = 7L,
        )

    // A lineup of this account, stored with [repairs]; the bytes as stored.
    private fun seed(repairs: TodayRepairs = record): ByteArray {
        runBlocking { newService().refresh(ACCOUNT, LIBRARIES) }
        val base = stored()
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(base.lineup, base.meta, repairs)) }
        store.calls.clear()
        events.clear()
        return checkNotNull(store.inner.peek(ACCOUNT))
    }

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    // --- A readable record of this account ----------------------------------------------------------------------

    @Test
    fun resetRecord_ownReadableRecord_isEmptied_published_andFloorsAtItsLatestSeq() =
        runBlocking<Unit> {
            seed()
            val service = newService()

            assertEquals(RecordReset.Floor(7L, record = true), service.resetRecord(ACCOUNT))

            assertEquals(TodayRepairs.NONE, stored().repairs, "every selection repair, every fix")
            assertEquals(TodayRepairs.NONE, service.repairs(ACCOUNT).value, "and published")
            assertTrue(checkNotNull(service.lineup(ACCOUNT).value).writable)
            assertEquals(listOf("load", "save"), store.calls)
        }

    @Test
    fun resetRecord_dropsTheLostCheckHeldInMemory_too() =
        runBlocking<Unit> {
            val check = LostCheck(1L, NOON.toEpochMilliseconds() + 3_600_000L)
            seed()
            val service = newService()
            assertTrue(
                service.recordRepairs(ACCOUNT) { it.repairs.copy(lostCheck = check) },
            )

            service.resetRecord(ACCOUNT)

            assertNull(service.repairs(ACCOUNT).value.lostCheck)
        }

    @Test
    fun floorRecord_ownReadableRecord_writesNothing() =
        runBlocking<Unit> {
            val bytes = seed()

            assertEquals(RecordReset.Floor(7L, record = true), newService().floorRecord(ACCOUNT))

            assertContentEquals(bytes, store.inner.peek(ACCOUNT))
            assertEquals(listOf("load"), store.calls)
        }

    @Test
    fun resetRecord_aWriteThatFails_isFailed_andTheFileStays() =
        runBlocking<Unit> {
            val bytes = seed()
            val service = newService()
            assertNotNull(service.loaded(ACCOUNT))
            store.inner.failWrites = true

            assertEquals(RecordReset.Failed, service.resetRecord(ACCOUNT))

            assertContentEquals(bytes, store.inner.peek(ACCOUNT))
            assertEquals(record, service.repairs(ACCOUNT).value, "nothing published")
        }

    // --- No file ------------------------------------------------------------------------------------------------

    @Test
    fun absent_floorsAtZero_withNoRecord_andWritesNothing() =
        runBlocking<Unit> {
            val service = newService()

            assertEquals(RecordReset.Floor(0L, record = false), service.resetRecord(ACCOUNT))
            assertEquals(RecordReset.Floor(0L, record = false), service.floorRecord(ACCOUNT))

            assertEquals(listOf("load", "load"), store.calls)
            assertNull(store.inner.peek(ACCOUNT))
        }

    // --- An unreadable file, or another account's ---------------------------------------------------------------

    @Test
    fun unreadable_isSetAside_andFloorsAtZero() =
        runBlocking<Unit> {
            for (reset in listOf(true, false)) {
                val garbage = "not a lineup".encodeToByteArray()
                store.inner.save(ACCOUNT, garbage)
                store.calls.clear()
                val service = newService()

                val got = if (reset) service.resetRecord(ACCOUNT) else service.floorRecord(ACCOUNT)

                assertEquals(RecordReset.Floor(0L, record = false), got)
                assertEquals(listOf("load", "setAside"), store.calls)
                assertNull(store.inner.peek(ACCOUNT))
                assertContentEquals(garbage, store.inner.setAsideCopy(ACCOUNT))
            }
        }

    @Test
    fun anotherAccountsFile_isSetAside_andFloorsAtZero() =
        runBlocking<Unit> {
            for (reset in listOf(true, false)) {
                val other = Lineup(id(9), USER, 1, 0, finalised = false, channels = emptyList())
                store.inner.save(ACCOUNT, LineupCodec.encode(other, RefreshMeta(), record))
                store.calls.clear()
                val service = newService()

                val got = if (reset) service.resetRecord(ACCOUNT) else service.floorRecord(ACCOUNT)

                assertEquals(RecordReset.Floor(0L, record = false), got, "another account's seqs are not a floor")
                assertEquals(listOf("load", "setAside"), store.calls)
                assertNull(store.inner.peek(ACCOUNT))
            }
        }

    @Test
    fun aSetAsideThatFails_isFailed_andTheFileStays() =
        runBlocking<Unit> {
            for (reset in listOf(true, false)) {
                val garbage = "not a lineup".encodeToByteArray()
                store.inner.failWrites = false
                store.inner.save(ACCOUNT, garbage)
                store.inner.failWrites = true
                val service = newService()

                val got = if (reset) service.resetRecord(ACCOUNT) else service.floorRecord(ACCOUNT)

                assertEquals(RecordReset.Failed, got)
                assertContentEquals(garbage, store.inner.peek(ACCOUNT))
            }
        }

    // --- A newer app's file ----------------------------------------------------------------------------------------

    @Test
    fun newerVersion_isLeftUntouched_andFloorsAtZero() =
        runBlocking<Unit> {
            val newer = """{"version":5,"whatever":"a newer app wrote"}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)
            store.calls.clear()
            val service = newService()

            assertEquals(RecordReset.Floor(0L, record = false), service.resetRecord(ACCOUNT))
            assertEquals(RecordReset.Floor(0L, record = false), service.floorRecord(ACCOUNT))

            assertEquals(listOf("load", "load"), store.calls, "never saved, never set aside")
            assertContentEquals(newer, store.inner.peek(ACCOUNT))
        }

    // --- A read that fails ----------------------------------------------------------------------------------------

    @Test
    fun aReadThatFails_isFailed_andWritesNothing() =
        runBlocking<Unit> {
            val bytes = seed()
            store.inner.failReads = true
            val service = newService()

            assertEquals(RecordReset.Failed, service.resetRecord(ACCOUNT))
            assertEquals(RecordReset.Failed, service.floorRecord(ACCOUNT))

            assertEquals(listOf("load", "load"), store.calls)
            store.inner.failReads = false
            assertContentEquals(bytes, store.inner.peek(ACCOUNT))
        }

    // --- The disk, not memory ---------------------------------------------------------------------------------------

    @Test
    fun theFileIsRead_notTheLineupHeldInMemory() =
        runBlocking<Unit> {
            seed()
            val service = newService()
            assertEquals(record, checkNotNull(service.loaded(ACCOUNT)).repairs, "held in memory")
            // A newer app has written the file since: memory still holds the old lineup and its record.
            val newer = """{"version":5}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)

            assertEquals(RecordReset.Floor(0L, record = false), service.floorRecord(ACCOUNT))
            assertEquals(RecordReset.Floor(7L, record = false), service.resetRecord(ACCOUNT), "the held record's seqs")

            assertContentEquals(newer, store.inner.peek(ACCOUNT), "the newer file is never written over")
            assertEquals(TodayRepairs.NONE, service.repairs(ACCOUNT).value, "what was held in memory shows no more")
            assertNotNull(service.lineup(ACCOUNT).value, "the lineup itself stays")
        }

    @Test
    fun aRecordHeldInMemory_isEmptied_whenTheFileIsGoneOrSetAside() =
        runBlocking<Unit> {
            for (gone in listOf(true, false)) {
                seed()
                val service = newService()
                assertEquals(record, checkNotNull(service.loaded(ACCOUNT)).repairs)
                if (gone) store.inner.forget(ACCOUNT) else store.inner.save(ACCOUNT, "not a lineup".encodeToByteArray())

                assertEquals(RecordReset.Floor(7L, record = false), service.resetRecord(ACCOUNT))

                assertEquals(TodayRepairs.NONE, service.repairs(ACCOUNT).value)
                assertNull(store.inner.peek(ACCOUNT), "nothing written in its place")
            }
        }

    @Test
    fun aRecordOnDisk_isFound_beforeAnythingIsLoaded() =
        runBlocking<Unit> {
            seed()
            val service = newService()
            assertNull(service.lineup(ACCOUNT).value, "nothing held yet")

            assertEquals(RecordReset.Floor(7L, record = true), service.floorRecord(ACCOUNT))
        }

    @Test
    fun anAccountThatIsNotCanonical_hasNoFile() =
        runBlocking<Unit> {
            val odd = LibraryAccount(ServerId("not-an-id"), UserId("nor-this"))
            val service = newService()

            assertEquals(RecordReset.Floor(0L, record = false), service.resetRecord(odd))
            assertEquals(RecordReset.Floor(0L, record = false), service.floorRecord(odd))
            assertEquals(emptyList(), store.calls)
        }

    // --- The trim hook ----------------------------------------------------------------------------------------------

    @Test
    fun theAppsConstructor_tellsTheTrimHookWhatASaveTrimmed() =
        runBlocking<Unit> {
            seed(TodayRepairs.NONE)
            val base = stored()
            // Fixes of days to come, none on air now: a file between the default cap less its margin and the cap.
            val probe = TodayRepairs(fixes = fixesFrom(PROBE))
            val each = (LineupCodec.encode(base.lineup, base.meta, probe).size - base.bytes()).toDouble() / PROBE
            val limit = LineupLimits.MAX_FILE_BYTES - RepairConfig().fileMargin
            val count = (((limit + LineupLimits.MAX_FILE_BYTES) / 2 - base.bytes()) / each).toInt()
            val bytes = LineupCodec.encode(base.lineup, base.meta, TodayRepairs(fixes = fixesFrom(count)))
            assertTrue(bytes.size in (limit + 1) until LineupLimits.MAX_FILE_BYTES, "${bytes.size}")
            store.inner.save(ACCOUNT, bytes)
            val trims = mutableListOf<Triple<Int, Int, Int>>()
            val note = { a: Int, b: Int, c: Int -> trims += Triple(a, b, c) }
            val service = LineupService(store, library, clock, { 0 }, {}, timed = {}, trimmed = note)

            service.refresh(ACCOUNT, LIBRARIES)

            val kept = stored().repairs.fixes.size
            assertTrue(kept < count, "the save trimmed the record")
            assertEquals(listOf(Triple(count - kept, 0, 0)), trims, "and the hook was told how many fixes went")
        }

    // [count] fixes of hour-long blocks to come, end to end from an hour ahead, none covering now.
    private fun fixesFrom(count: Int) =
        (0 until count).map {
            val start = NOON.toEpochMilliseconds() + (1L + it) * 3_600_000L
            fixOf(n = 1L + it / 24, dayEndMs = start + 86_400_000L, blockStartMs = start)
        }

    private fun LineupCodec.Decoded.Loaded.bytes(): Int = LineupCodec.encode(lineup, meta, TodayRepairs.NONE).size
}

private const val PROBE = 2_000
