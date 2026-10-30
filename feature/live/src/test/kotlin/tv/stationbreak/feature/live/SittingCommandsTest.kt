// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One sitting's inbox: ops for another sitting or another block never act (spec 20, ADR-0021 decision 9). */
class SittingCommandsTest {
    @Test
    fun anOpTaggedForAnOldSittingIsDropped() =
        runTest {
            val inbox = SittingCommands(2)
            assertFalse(inbox.offer(Tagged(1, 100, TransportOp.Toggle)))
            assertTrue(inbox.offer(Tagged(2, 100, TransportOp.Pause)))
            assertEquals(TransportOp.Pause, inbox.receive(100))
        }

    @Test
    fun anOpTaggedForAnotherBlockIsDropped() =
        runTest {
            val inbox = SittingCommands(2)
            inbox.offer(Tagged(2, 100, TransportOp.Toggle))
            inbox.offer(Tagged(2, 200, TransportOp.Pause))
            assertEquals(TransportOp.Pause, inbox.receive(200))
        }

    @Test
    fun opsArriveInOrder() =
        runTest {
            val inbox = SittingCommands(1)
            val ops = listOf(TransportOp.Play, TransportOp.Pause, TransportOp.GoLive, TransportOp.StepReleased)
            ops.forEach { inbox.offer(Tagged(1, 5, it)) }
            assertEquals(ops, ops.map { inbox.receive(5) })
        }

    @Test
    fun theOldestIsDroppedPastCapacity() =
        runTest {
            val inbox = SittingCommands(1)
            repeat(SittingCommands.CAPACITY + 1) {
                inbox.offer(Tagged(1, 5, TransportOp.Step(SeekDirection.Back, false, it.toLong())))
            }
            val first = inbox.receive(5) as TransportOp.Step
            assertEquals(1L, first.atMs)
        }

    @Test
    fun drainEmptiesAndCounts() =
        runTest {
            val inbox = SittingCommands(1)
            repeat(3) { inbox.offer(Tagged(1, 5, TransportOp.Toggle)) }
            assertEquals(3, inbox.drain())
            assertEquals(0, inbox.drain())
        }
}
