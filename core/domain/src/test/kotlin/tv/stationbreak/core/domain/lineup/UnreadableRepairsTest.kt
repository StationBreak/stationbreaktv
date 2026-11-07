// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.fixOf
import tv.stationbreak.core.domain.lineup.repair.repairOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A stored repair record that does not read (spec 26): the lineup loads with none, and is never set aside. */
class UnreadableRepairsTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private fun serviceWith(report: (LineupEvent) -> Unit = {}) =
        LineupService(store, library, clock, { 0 }, report, LineupSeams(TestCatalog))

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    @Test
    fun anUnreadableRecord_loadsTheLineupWithNone_reportsIt_andIsNeverSetAside() =
        runBlocking<Unit> {
            val nowMs = NOON.toEpochMilliseconds()
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(selection = listOf(repairOf(atMs = nowMs))) })
            val text = checkNotNull(store.inner.peek(ACCOUNT)).decodeToString()
            val broken = text.replaceFirst("\"repairs\":{", "\"repairs\":{\"surprise\":1,")
            assertTrue(broken != text)
            store.inner.save(ACCOUNT, broken.encodeToByteArray())
            val events = mutableListOf<LineupEvent>()
            val fresh = serviceWith { events += it }

            val held = checkNotNull(fresh.loaded(ACCOUNT))

            assertEquals(stored().lineup, held.lineup, "the lineup loads")
            assertEquals(TodayRepairs.NONE, held.repairs, "with no record")
            assertEquals(listOf(LineupEvent.RepairsUnreadable), events)
            assertNull(store.inner.setAsideCopy(ACCOUNT), "the file is not set aside")
            assertEquals(broken, store.inner.keptCopy(ACCOUNT, "repairs-unreadable")?.decodeToString(), "kept first")
            assertFalse(stored().repairsUnreadable, "the file is written once without the record")
            assertEquals(TodayRepairs.NONE, stored().repairs)
            assertEquals(3, stored().fileVersion)
            // Reported once: neither this service nor a later one sees it again.
            fresh.recordRepairs(ACCOUNT) { it.repairs }
            val later = mutableListOf<LineupEvent>()
            checkNotNull(serviceWith { later += it }.loaded(ACCOUNT))
            assertEquals(listOf(LineupEvent.RepairsUnreadable), events)
            assertEquals(emptyList(), later)
            // The next record is saved over it, readable again.
            val next = TodayRepairs(fixes = listOf(fixOf(blockStartMs = nowMs)))
            assertTrue(fresh.recordRepairs(ACCOUNT) { next })
            assertEquals(next, stored().repairs)
            assertFalse(stored().repairsUnreadable)
        }
}
