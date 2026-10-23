// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult

class BenchSignOutTest : SetupFixture() {
    @Test
    fun signOutRevokesTheStoredSession() =
        runTest {
            sessions.save(session)
            settings.save(ServerRecord(address))
            fake.resumeResult = ServerResult.Success(session)

            setup.signOut()

            assertEquals(listOf("SIGNOUT OK revoked"), lines)
            assertNull(stored())
            assertEquals(1, fake.revokeCalls.size)
        }

    @Test
    fun signOutSaysWhenTheServerDidNotRevoke() =
        runTest {
            sessions.save(session)
            settings.save(ServerRecord(address))
            fake.resumeResult = ServerResult.Success(session)
            fake.revokeResult = ServerResult.Failure(MediaServerError.Timeout())

            setup.signOut()

            assertEquals(listOf("SIGNOUT OK not-revoked"), lines)
            assertNull(stored())
        }

    @Test
    fun signOutWithoutASessionIsAnError() =
        runTest {
            setup.signOut()
            settings.save(ServerRecord(address))
            setup.signOut()

            assertEquals(listOf("SIGNOUT ERROR no-session", "SIGNOUT ERROR no-session"), lines)
            assertTrue(fake.revokeCalls.isEmpty())
        }

    @Test
    fun aSessionTheServerRefusesIsClearedAndReportedNotRevoked() =
        runTest {
            signedInDevice()
            fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            setup.signOut()

            assertEquals(listOf("SIGNOUT OK not-revoked"), lines)
            assertNull(stored())
        }

    @Test
    fun anUnreachableServerStopsSignOutUnlessForced() =
        runTest {
            signedInDevice()
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())

            setup.signOut()
            assertEquals(listOf("SIGNOUT ERROR unreachable"), lines)
            assertNotNull(stored())

            setup.signOut(force = true)
            assertEquals("SIGNOUT OK not-revoked", lines.last())
            assertNull(stored())
        }

    @Test
    fun forceDropsASessionThatHasNoServerRecord() =
        runTest {
            sessions.save(session)

            setup.signOut()
            assertEquals("SIGNOUT ERROR no-session", lines.last())
            assertNotNull(stored())

            setup.signOut(force = true)
            assertEquals("SIGNOUT OK not-revoked", lines.last())
            assertNull(stored())
        }
}
