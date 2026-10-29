// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import kotlin.time.Duration.Companion.hours

/**
 * Why a stay-current run that did nothing did nothing: the numbers-only `StayCurrentSkipped` line, one per exit, and
 * the `StayCurrentDone` line of a run whose passes threw.
 */
class StayCurrentSkipLinesTest {
    private val lines = mutableListOf<String>()
    private val rig = StayCurrentRig { lines += it }
    private val server = rig.server
    private val both = SyncPlan(incremental = true, reconcile = true)

    @After
    fun close() = rig.close()

    @Test
    fun anEmptyPlanSaysSo() =
        runBlocking {
            rig.firstSyncDone()

            rig.runner.run(SyncPlan.None)

            assertEquals(listOf("StayCurrentSkipped plan=false/false reason=EmptyPlan"), lines)
        }

    @Test
    fun aSecondConcurrentRunSaysItWasBusy() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { call ->
                if (call.number == 1) runBlocking { rig.runner.run(both) }
                null
            }

            rig.runner.run(both)

            assertEquals(1, lines.count { it == "StayCurrentSkipped plan=true/true reason=Busy" })
        }

    @Test
    fun noChosenLibrariesSaysSo() =
        runBlocking {
            rig.firstSyncDone()
            rig.settings.clearLibraries()

            rig.runner.run(SyncPlan(incremental = false, reconcile = true))

            assertEquals(listOf("StayCurrentSkipped plan=false/true reason=NoLibraries"), lines)
        }

    @Test
    fun moreThanOneActiveAccountSaysSoWithTheCount() =
        runBlocking {
            val active =
                rig.repository.dao
                    .activeAccounts()
                    .size
            assertTrue(active != 1)

            rig.runner.run(both)

            assertEquals(listOf("StayCurrentSkipped plan=true/true reason=NoAccount accounts=$active"), lines)
        }

    @Test
    fun anUnfinishedFirstSyncSaysSo() =
        runBlocking {
            rig.sessions.save(SyncWorld.session)
            rig.fake.resumeResult = ServerResult.Success(SyncWorld.session)

            rig.runner.run(both)

            assertEquals(listOf("StayCurrentSkipped plan=true/true reason=FirstSyncOpen"), lines)
        }

    @Test
    fun aSignedOutSessionSaysSo() =
        runBlocking {
            rig.firstSyncDone()
            rig.sessions.clear()

            rig.runner.run(both)

            assertEquals(listOf("StayCurrentSkipped plan=true/true reason=SignedOut"), lines)
        }

    @Test
    fun aRejectedSessionSaysSo() =
        runBlocking {
            rig.firstSyncDone()
            rig.fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            rig.runner.run(both)

            assertEquals(listOf("StayCurrentSkipped plan=true/true reason=SessionRejected"), lines)
        }

    @Test
    fun anUnreachableServerSaysSoWithTheFailureName() =
        runBlocking {
            rig.firstSyncDone()
            rig.fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())

            rig.runner.run(both)

            assertEquals(listOf("StayCurrentSkipped plan=true/true reason=Unavailable failure=Network"), lines)
        }

    @Test
    fun anExceptionFromThePassReportsAFailedRunOnce() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { throw IllegalStateException("secret message") }

            rig.runner.run(both)

            assertEquals(1, lines.count { it.startsWith("StayCurrentDone ") })
            assertTrue(lines.toString(), "StayCurrentDone ms=0 outcome=Failed failure=Server" in lines)
            assertTrue(lines.none { it.contains("secret") })
        }
}
