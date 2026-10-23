// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind

class BenchSetupTest {
    private val session = BenchTestData.session
    private val address = session.address
    private val fake = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, fake)
    private val settings = InMemoryServerSettings()
    private val lines = mutableListOf<String>()
    private val setup = BenchSetup(fake, sessions, settings, lines::add)

    private val libraries =
        listOf(
            Library(ItemId("films"), "Films", LibraryKind.Movies),
            Library(ItemId("home"), "Home", LibraryKind.HomeVideos),
        )

    private fun approvedSignIn() {
        fake.discoverResult = ServerResult.Success(ResolvedServer(address, session.server))
        fake.quickConnectStates =
            listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Waiting, QuickConnectState.SignedIn(session))
        fake.librariesResult = ServerResult.Success(libraries)
    }

    private fun stored() = store.snapshot()[SessionRepository.SESSION_KEY]

    @Test
    fun setupSignsInSavesTheSessionAndTheDefaultLibraries() =
        runTest {
            approvedSignIn()

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP OK"), lines)
            assertEquals(listOf("media.example.invalid"), fake.discoverCalls)
            assertEquals(address, fake.quickConnectCalls.single().first)
            assertNotNull(stored())
            assertEquals(ServerRecord(address), settings.current)
            // Home videos are left out, as the picker's defaults leave them.
            assertEquals(setOf(ItemId("films")), settings.currentLibraries)
        }

    @Test
    fun aStoredSessionIsNeverOverwritten() =
        runTest {
            approvedSignIn()
            sessions.save(session)
            settings.save(ServerRecord(address))
            fake.resumeResult = ServerResult.Success(session)

            setup.setup("media.example.invalid")

            assertEquals(listOf("SETUP ERROR already-signed-in"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
            assertTrue(fake.quickConnectCalls.isEmpty())
        }

    @Test
    fun aDeniedCodeIsAnErrorAndSavesNothing() =
        runTest {
            approvedSignIn()
            fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Denied)

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP ERROR qc-denied"), lines)
            assertNull(stored())
            assertNull(settings.current)
        }

    @Test
    fun aFlowThatEndsWithoutAnAnswerIsAnExpiredCode() =
        runTest {
            approvedSignIn()
            fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Waiting)

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP ERROR qc-expired"), lines)
        }

    @Test
    fun anAddressTheServerRefusesIsAReasonCodeOnly() =
        runTest {
            fake.discoverResult = ServerResult.Failure(MediaServerError.InvalidAddress())

            setup.setup("not an address")

            assertEquals(listOf("SETUP ERROR bad-server"), lines)
            assertTrue(fake.quickConnectCalls.isEmpty())
        }

    @Test
    fun cleartextToAPublicHostIsAcknowledgedBecauseTheOperatorTypedIt() =
        runTest {
            approvedSignIn()
            val cleartext = address.copy(scheme = tv.stationbreak.core.model.Scheme.Http, port = 80)
            fake.discoverResult = ServerResult.Success(ResolvedServer(cleartext, session.server))

            setup.setup("http://media.example.invalid")

            assertTrue(cleartext.cleartextNeedsConfirmation)
            assertEquals(
                cleartext,
                fake.quickConnectCalls
                    .single()
                    .second
                    ?.address,
            )
        }

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
    fun onlyHomeVideosIsNoLibraries() =
        runTest {
            approvedSignIn()
            fake.librariesResult = ServerResult.Success(listOf(libraries[1]))

            setup.setup("media.example.invalid")

            assertEquals("SETUP ERROR no-libraries", lines.last())
            assertNull(stored())
        }

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
    fun noLineHoldsTheAddressATokenOrALibraryName() =
        runTest {
            approvedSignIn()
            setup.setup("media.example.invalid")
            setup.signOut()

            val text = lines.joinToString("\n")
            assertFalse(text.contains("example"))
            assertFalse(text.contains("tok"))
            assertFalse(text.contains("Films"))
        }

    @Test
    fun reasonCodesAreShortFixedWords() {
        val errors =
            listOf(
                MediaServerError.DnsFailure(),
                MediaServerError.TlsUntrusted(),
                MediaServerError.NotJellyfin(),
                MediaServerError.CleartextNotConfirmed(),
                MediaServerError.QuickConnectDisabled(),
                MediaServerError.HttpUnauthorized(),
                MediaServerError.ServerError(),
            )
        assertEquals(
            listOf("unreachable", "tls", "unsupported-server", "cleartext", "qc-disabled", "refused", "server-error"),
            errors.map(SetupReason::of),
        )
    }
}
