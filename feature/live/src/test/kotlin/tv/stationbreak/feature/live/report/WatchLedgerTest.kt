// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The interval tables: times are wall minutes from a zero origin, `fromMs` and positions are media time. */
class WatchLedgerTest {
    private val t0 = Instant.fromEpochMilliseconds(0)
    private val a = AiringKey(ChannelId("c1"), 0, ItemId("a"))
    private val b = AiringKey(ChannelId("c1"), 0, ItemId("b"))

    private fun min(m: Int): Instant = t0 + m.minutes

    private fun media(m: Double): Long = (m * 60_000).toLong()

    private fun ledger() = WatchLedger(ReportingConfig())

    private fun WatchLedger.enterAt(
        key: AiringKey,
        atMin: Int,
        fromMin: Int,
    ) = enter(key, min(atMin), media(fromMin.toDouble()))

    @Test
    fun `live viewing counts wall time as before`() {
        val l = ledger()
        l.enterAt(a, 0, 30)
        l.leave(min(10))
        assertEquals(10.minutes, l.watched(a, min(10)))
    }

    @Test
    fun `the open interval counts up to now`() {
        val l = ledger()
        l.enterAt(a, 0, 30)
        assertEquals(4.minutes, l.watched(a, min(4)))
    }

    @Test
    fun `rewatching after going back counts once`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(10))
        l.seeked(min(10), media(8.0))
        l.leave(min(14))
        assertEquals(12.minutes, l.watched(a, min(14)))
    }

    @Test
    fun `skipping ahead counts nothing`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(1))
        l.seeked(min(1), media(6.0))
        l.leave(min(2))
        assertEquals(2.minutes, l.watched(a, min(2)))
    }

    @Test
    fun `a seeks rebuffer counts nothing`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(5))
        l.seeked(min(7), media(8.0))
        l.leave(min(10))
        assertEquals(8.minutes, l.watched(a, min(10)))
    }

    @Test
    fun `exact stop positions win over derived ones`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.stall(min(10), positionMs = media(9.0))
        l.resumed(min(11))
        l.leave(min(12))
        assertEquals(10.minutes, l.watched(a, min(12)))

        val p = ledger()
        p.enterAt(a, 0, 0)
        p.pause(min(5), positionMs = media(4.5))
        assertEquals(4.5.minutes, p.watched(a, min(5)))
        p.unpause(min(6))
        p.leave(min(7))
        assertEquals(5.5.minutes, p.watched(a, min(7)))
    }

    @Test
    fun `a pause counts nothing`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.pause(min(5))
        l.unpause(min(15))
        l.leave(min(20))
        assertEquals(10.minutes, l.watched(a, min(20)))
    }

    @Test
    fun `a stall resumed while paused counts nothing`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.pause(min(5))
        l.stall(min(6))
        l.resumed(min(7))
        l.leave(min(20))
        assertEquals(5.minutes, l.watched(a, min(20)))
    }

    @Test
    fun `a retry at the place counts the gap as nothing`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.stall(min(5))
        l.resumed(min(6), media(5.0))
        l.leave(min(10))
        assertEquals(9.minutes, l.watched(a, min(10)))
    }

    @Test
    fun `flipping away and back unites the intervals`() {
        val l = ledger()
        l.enterAt(a, 0, 10)
        l.enterAt(b, 5, 0)
        l.enterAt(a, 10, 12)
        l.leave(min(15))
        assertEquals(7.minutes, l.watched(a, min(15)))
    }

    @Test
    fun `mark is due on the union`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(10))
        l.seeked(min(10), 0)
        l.seeking(min(20))
        l.seeked(min(20), 0)
        assertEquals(10.minutes, l.watched(a, min(30)))
        assertFalse(l.markDue(a, 20.minutes, min(30)))
        assertEquals(18.minutes, l.watched(a, min(38)))
        assertTrue(l.markDue(a, 20.minutes, min(38)))
    }

    @Test
    fun `intervals are clipped at zero`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(1))
        l.seeked(min(1), -5_000)
        l.leave(min(2))
        assertEquals(1.minutes, l.watched(a, min(2)))
    }

    @Test
    fun `an exact position below where the span began drops the span and never over-counts`() {
        val l = ledger()
        l.enterAt(a, 0, 30)
        l.stall(min(5), media(20.0))
        l.leave(min(6))
        assertEquals(0.minutes, l.watched(a, min(6)))
    }

    @Test
    fun `a seek during a pause counts only the time played before and after`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.pause(min(4), media(4.0))
        l.seeking(min(5))
        l.seeked(min(6), media(1.0))
        l.unpause(min(7))
        l.leave(min(10))
        // 0-4 before the pause, then 1-4 again after the seek and the unpause: all already watched, so 4 in all.
        assertEquals(4.minutes, l.watched(a, min(10)))
    }

    @Test
    fun `a stall during a seek then resumed runs from where the seek landed`() {
        val l = ledger()
        l.enterAt(a, 0, 0)
        l.seeking(min(2))
        l.stall(min(3), null)
        l.seeked(min(4), media(1.0))
        l.resumed(min(5), null)
        l.leave(min(8))
        // 0-2 before the seek, then 1-4 once the stall is over: 1-2 is already watched, so 4 in all.
        assertEquals(4.minutes, l.watched(a, min(8)))
    }
}
