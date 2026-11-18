// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [LineupRefresher.working]: true while a sync run writes the library and until the check its end owes has run, so a
 * reader never sees the refresher idle between the end of a run and the lineup that run leads to (the first lineup
 * after setup).
 */
class LineupRefresherWorkingTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val writing = MutableStateFlow(false)
    private val job = Job()
    private val scope = CoroutineScope(job)
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(InMemoryLineupStore(), library, clock, { 0 }, {}, TestCatalog)
    private val refresher =
        LineupRefresher(service, library, { LIBRARIES }, SyncActivity { writing }, NO_CHANGES) {}

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(10) }
        check(condition()) { what }
    }

    @Test
    fun idleOnceTheStartCheckHasRun() =
        runBlocking<Unit> {
            refresher.start(scope)

            until("the start refresh planned") { service.loaded(ACCOUNT) != null }
            until("idle") { !refresher.working.value }
        }

    @Test
    fun workingFromARunsWritingUntilTheCheckItsEndOwesHasRun() =
        runBlocking<Unit> {
            refresher.start(scope)
            until("the start refresh planned") { service.loaded(ACCOUNT) != null }
            until("idle") { !refresher.working.value }
            val before = checkNotNull(service.loaded(ACCOUNT)).revision

            writing.value = true
            until("working while the run writes") { refresher.working.value }
            // The run writes: the lineup is behind once it ends.
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T11:00:00Z")))
            val release = CountDownLatch(1)
            library.afterSnapshot = { release.await() }

            writing.value = false
            until("the end's refresh is reading the library") { library.snapshotCutoffs.size >= 2 }
            delay(SETTLE_MS)
            assertTrue(refresher.working.value, "still working while the refresh the run's end asked for runs")

            release.countDown()
            until("idle again") { !refresher.working.value }
            assertTrue(checkNotNull(service.loaded(ACCOUNT)).revision > before, "and only once it has published")
        }

    @Test
    fun aRunAlreadyWritingWhenTheFollowBeginsCounts() =
        runBlocking<Unit> {
            writing.value = true
            refresher.start(scope)

            until("working") { refresher.working.value }
            delay(SETTLE_MS)
            assertTrue(refresher.working.value, "the run still writes")

            writing.value = false
            until("idle once it ends") { !refresher.working.value }
        }
}

private const val WAIT_MS = 10_000L
private const val SETTLE_MS = 250L
