// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedCall
import kotlin.time.Duration.Companion.hours
import kotlin.time.TestTimeSource

/**
 * The `StayCurrentDone` and `BackfillDone` lines of a run that ends in an Error (an OutOfMemoryError, say): the class
 * name and nothing else, and the Error goes on unchanged, even when the log cannot be written.
 */
class StayCurrentErrorLinesTest {
    private val lines = mutableListOf<String>()
    private val time = TestTimeSource()
    private val rig = StayCurrentRig(timeSource = time) { lines += it }
    private val server = rig.server
    private val both = SyncPlan(incremental = true, reconcile = true)

    @After
    fun close() = rig.close()

    @Test
    fun aPassThatEndsInAnErrorReportsItByClassNameAndTheErrorGoesOn() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val broken = HeapGone("a secret message")
            server.respond = { throw broken }

            val escaped = runCatching { rig.runner.run(both) }.exceptionOrNull()

            assertSame(broken, escaped)
            assertTrue(lines.toString(), "StayCurrentDone ms=0 outcome=Error error=HeapGone" in lines)
            assertTrue(lines.toString(), lines.none { it.contains("secret") })
            assertFalse(rig.runner.isRunning)
        }

    @Test
    fun aReporterThatFailsWhileAnErrorEscapesDoesNotReplaceTheError() =
        runBlocking {
            val loud =
                StayCurrentRig(
                    report = { if (it.startsWith("StayCurrentDone")) throw HeapGone("the log is out of memory") },
                )
            try {
                loud.firstSyncDone()
                loud.clock.instant += 7.hours
                val broken = HeapGone("the pass")
                loud.server.respond = { throw broken }

                val escaped = runCatching { loud.runner.run(both) }.exceptionOrNull()

                assertSame(broken, escaped)
            } finally {
                loud.close()
            }
        }

    @Test
    fun aRereadThatEndsInAnErrorReportsItByClassNameAndTheErrorGoesOn() =
        runBlocking {
            rig.firstSyncDone()
            rig.repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)
            rig.repository.dao.setBackfillPending(ALICE.server.value, ALICE.user.value, true)
            rig.clock.instant += 7.hours
            val broken = HeapGone("a secret message")
            server.respond = { call -> if (isBackfill(call)) throw broken else null }

            val escaped = runCatching { rig.runner.run(both) }.exceptionOrNull()

            assertSame(broken, escaped)
            assertTrue(lines.toString(), "BackfillDone ms=0 outcome=Error error=HeapGone" in lines)
            assertTrue(lines.toString(), lines.none { it.contains("secret") })
        }

    // A full walk's item page: no window and not an ID listing. Only the backfill asks for one in these runs.
    private fun isBackfill(call: PagedCall) = !call.idsOnly && call.changedSince == null
}

// A stand-in for an OutOfMemoryError: an Error that is not an Exception.
private class HeapGone(
    message: String,
) : Error(message)
