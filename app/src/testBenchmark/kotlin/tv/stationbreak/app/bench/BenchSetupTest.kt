// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import kotlin.time.Duration.Companion.seconds

/** A server whose Quick Connect can be made to wait for ever (the user has not approved yet). */
private class WaitingServer(
    private val inner: FakeMediaServer,
) : MediaServer by inner {
    var waits = false

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> =
        if (waits) {
            flow {
                emit(QuickConnectState.CodeIssued("111111"))
                awaitCancellation()
            }
        } else {
            inner.quickConnect(address, acknowledgement)
        }
}

/** Settings that can run a hook just before the library choice is stored, to cancel or fail the run there. */
private class HookedSettings(
    private val inner: InMemoryServerSettings,
) : ServerSettings by inner {
    var beforeSaveLibraries: () -> Unit = {}

    override suspend fun saveLibraries(ids: Set<ItemId>): Boolean {
        beforeSaveLibraries()
        return inner.saveLibraries(ids)
    }
}

class BenchSetupTest {
    private val session = BenchTestData.session
    private val address = session.address
    private val fake = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, fake)
    private val settings = InMemoryServerSettings()
    private val hooked = HookedSettings(settings)
    private val waiting = WaitingServer(fake)
    private val lines = mutableListOf<String>()
    private val setup = BenchSetup(waiting, sessions, hooked, store, lines::add)

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
    fun anExplicitHttpAddressIsAcknowledgedBecauseTheOperatorTypedIt() =
        runTest {
            approvedSignIn()
            val cleartext = address.copy(scheme = Scheme.Http, port = 80)
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
    fun anHttpAddressReachedByFallbackIsRefusedAsCleartext() =
        runTest {
            approvedSignIn()
            val fallback = address.copy(scheme = Scheme.Http, port = 80, viaFallback = true)
            fake.discoverResult = ServerResult.Success(ResolvedServer(fallback, session.server))

            setup.setup("https://media.example.invalid")

            assertEquals(listOf("SETUP ERROR cleartext"), lines)
            assertTrue(fake.quickConnectCalls.isEmpty())
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
    fun aSessionStoredWithNoServerRecordIsStillASession() =
        runTest {
            approvedSignIn()
            sessions.save(session)

            setup.setup("https://media.example.invalid")

            assertNull(settings.current)
            assertEquals(listOf("SETUP ERROR already-signed-in"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
        }

    @Test
    fun anUnreadableStoreIsCantTellAndNothingIsOverwritten() =
        runTest {
            approvedSignIn()
            store.failLoad = true

            setup.setup("https://media.example.invalid")

            assertEquals(listOf("SETUP ERROR store-unreadable"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
            assertTrue(store.writtenKeys.isEmpty())
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

    private suspend fun signedInDevice() {
        sessions.save(session)
        settings.save(ServerRecord(address))
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
