// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionResume

/**
 * The selection's resume at the app's start (spec 26, Part 4, A6): whatever happens, even a selection that cannot be
 * built, the seam opens, so nothing waits on it for ever, and a failure is told by name.
 */
class SelectionStartTest {
    @Test
    fun aSelectionThatCannotBeBuilt_isReported_andTheGateStillOpens() =
        runBlocking {
            val gate = ResumeGate()
            val waiting = async { gate.await() }
            val events = mutableListOf<String>()

            resumeSelection({ error("the graph could not build it") }, gate) { events += it }

            withTimeout(5_000) { waiting.await() }
            assertEquals(listOf("SelectionResumeFailed"), events)
        }

    @Test
    fun aResume_opensTheGateOnlyOnceItHasRun() =
        runBlocking {
            val gate = ResumeGate()
            val ran = mutableListOf<Boolean>()
            val waiting = async { gate.await() }
            val resume = SelectionResume { ran += waiting.isCompleted }
            repeat(5) { yield() }
            assertFalse(waiting.isCompleted)

            resumeSelection({ resume }, gate) {}

            withTimeout(5_000) { waiting.await() }
            assertEquals(listOf(false), ran)
        }
}
