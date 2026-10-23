// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import kotlin.time.Duration.Companion.seconds

class BenchSetupRollbackTest : SetupFixture() {
    @Test
    fun aFailureAfterSignInSignsOutAgainAndRevokesTheToken() =
        runTest {
            approvedSignIn()
            fake.librariesResult = ServerResult.Failure(MediaServerError.Timeout())

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP ERROR unreachable"), lines)
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }

    @Test
    fun aCodeNobodyApprovesExpiresAtTheWaitLimit() =
        runTest {
            approvedSignIn()
            waiting.waits = true
            val limited = BenchSetup(waiting, sessions, hooked, store, lines::add, waitLimit = 5.seconds)

            limited.setup("https://media.example.invalid")

            assertEquals(listOf("QC 111111", "SETUP ERROR qc-expired"), lines)
            assertNull(stored())
        }

    @Test
    fun cancellationAfterSignInRevokesTheTokenAndLeavesNothingStored() =
        runTest {
            approvedSignIn()
            val job = launch { setup.setup("https://media.example.invalid") }
            hooked.beforeSaveLibraries = { job.cancel() }

            job.join()

            assertEquals("SETUP ERROR cancelled", lines.last())
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }

    @Test
    fun anExceptionWhileStoringRollsTheSignInBack() =
        runTest {
            approvedSignIn()
            hooked.beforeSaveLibraries = { error("boom") }

            val thrown = runCatching { setup.setup("https://media.example.invalid") }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
            assertEquals("SETUP ERROR run-failed", lines.last())
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun aSecondSetupOrSignOutWhileOneRunsIsBusy() =
        runTest {
            approvedSignIn()
            waiting.waits = true
            val first = launch { setup.setup("https://media.example.invalid") }
            runCurrent()

            setup.setup("https://media.example.invalid")
            setup.signOut()
            first.cancel()
            first.join()

            assertEquals(
                listOf("QC 111111", "SETUP ERROR busy", "SIGNOUT ERROR busy", "SETUP ERROR cancelled"),
                lines,
            )
            // The lock is free again.
            setup.signOut()
            assertEquals("SIGNOUT ERROR no-session", lines.last())
        }

    @Test
    fun aServerRecordThatCannotBeSavedRollsBack() =
        runTest {
            approvedSignIn()
            settings.failSave = true

            setup.setup("https://media.example.invalid")

            assertEquals("SETUP ERROR settings-not-saved", lines.last())
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }

    @Test
    fun aSessionThatCannotBeSavedRollsBack() =
        runTest {
            approvedSignIn()
            store.failSave = true

            setup.setup("https://media.example.invalid")

            assertEquals("SETUP ERROR session-not-saved", lines.last())
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }
}
