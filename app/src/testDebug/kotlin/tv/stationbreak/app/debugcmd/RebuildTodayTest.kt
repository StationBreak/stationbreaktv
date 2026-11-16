// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.debugcmd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.stationbreak.core.domain.lineup.RebuildTodayResult

/** What the rebuild command says in logcat (#812): outcome and timing only, never a message that could hold a URL. */
class RebuildTodayTest {
    private val lines = mutableListOf<String>()

    private fun run(block: suspend () -> RebuildTodayResult) = runBlocking { runRebuildToday(block) { lines += it } }

    @Test
    fun saysStartedThenDoneWithTheTime() {
        run { RebuildTodayResult.Done }
        assertEquals("started", lines[0])
        assertTrue(lines[1], Regex("done in \\d+ ms").matches(lines[1]))
        assertEquals(2, lines.size)
    }

    @Test
    fun saysWhyItSkipped() {
        run { RebuildTodayResult.NoAccount }
        run { RebuildTodayResult.NoLibraries }
        run { RebuildTodayResult.NoLineup }
        assertEquals(
            listOf(
                "started",
                "skipped: nobody is signed in",
                "started",
                "skipped: no libraries are chosen",
                "started",
                "skipped: account",
            ),
            lines,
        )
    }

    @Test
    fun aFailureIsReportedByClassNameOnly() {
        run { throw IllegalStateException("https://jelly.example/Items/abc?api_key=secret") }
        assertEquals(listOf("started", "failed: IllegalStateException"), lines)
    }

    @Test
    fun cancellationIsNotAFailure() {
        try {
            run { throw CancellationException("stop") }
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf("started"), lines)
        }
    }
}
