// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStoreResult
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.SpkiPin

/** Confirming a pin (spec 05, Unit 3): the captured SPKI is trusted, probed afresh, and only then stored. */
class CertificateTrustTest {
    private val server = FakeMediaServer()
    private val settings = InMemoryServerSettings()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server)
    private val trust = CertificateTrust(server, settings, sessions)

    private val spki = SpkiPin(ByteArray(SpkiPin.SIZE) { 6 })
    private val certificate = CertificateEvidence(spki, selfSigned = true, notBeforeMillis = 1L, notAfterMillis = 2L)
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val resolved = ResolvedServer(address, info)

    private fun prompt(reason: TrustReason = TrustReason.Untrusted) = TrustPrompt(reason, certificate)

    @Test
    fun itTrustsExactlyTheCapturedSpkiForTheTypedInput() =
        runBlocking {
            server.trustResult = ServerResult.Success(resolved)
            trust.confirm("nas.example.test:8920", prompt())
            assertEquals(listOf("nas.example.test:8920" to spki), server.trustCalls)
        }

    @Test
    fun onSuccessTheAddressAndThePinAreStoredTogether() =
        runBlocking {
            server.trustResult = ServerResult.Success(resolved)
            val outcome = trust.confirm("nas.example.test:8920", prompt())
            assertEquals(TrustOutcome.Trusted(resolved), outcome)
            assertEquals(listOf(ServerRecord(address, spki)), settings.saved)
        }

    @Test
    fun noCredentialIsEverSentAndNothingIsStoredOnAFailure() =
        runBlocking {
            val error = MediaServerError.TlsCertificateChanged(certificate = certificate)
            server.trustResult = ServerResult.Failure(error)
            val outcome = trust.confirm("nas.example.test:8920", prompt())
            assertEquals(TrustOutcome.Refused(error), outcome)
            assertTrue(settings.saved.isEmpty())
            assertNull(settings.current)
            assertTrue(server.signInCalls.isEmpty())
            assertTrue(server.quickConnectCalls.isEmpty())
        }

    @Test
    fun anyConfirmationDropsTheStoredSessionNotOnlyAChangedCertificate() =
        runBlocking {
            for (reason in TrustReason.entries) {
                store.save(SessionRepository.SESSION_KEY, "stored")
                server.trustResult = ServerResult.Success(resolved)
                trust.confirm("nas.example.test:8920", prompt(reason))
                assertEquals(reason.name, SecretStoreResult.Success(null), store.load(SessionRepository.SESSION_KEY))
            }
        }

    @Test
    fun aRefusedConfirmationAlsoDropsTheStoredSession() =
        runBlocking {
            store.save(SessionRepository.SESSION_KEY, "stored")
            server.trustResult = ServerResult.Failure(MediaServerError.Timeout())
            trust.confirm("nas.example.test:8920", prompt())
            assertEquals(SecretStoreResult.Success(null), store.load(SessionRepository.SESSION_KEY))
        }

    @Test
    fun theSessionIsGoneBeforeTrustIsCalled() =
        runBlocking {
            store.save(SessionRepository.SESSION_KEY, "stored")
            var sessionWhenTrustRan: SecretStoreResult<String?>? = null
            val watching =
                object : MediaServer by server {
                    override suspend fun trust(
                        input: String,
                        pin: SpkiPin,
                    ): ServerResult<ResolvedServer> {
                        sessionWhenTrustRan = store.load(SessionRepository.SESSION_KEY)
                        return ServerResult.Success(resolved)
                    }
                }
            CertificateTrust(watching, settings, sessions).confirm("nas.example.test:8920", prompt())
            assertEquals(SecretStoreResult.Success(null), sessionWhenTrustRan)
        }

    @Test
    fun aRecordThatCouldNotBeSavedIsNotConnectedAndTheTrustIsWithdrawn() =
        runBlocking {
            server.trustResult = ServerResult.Success(resolved)
            settings.failSave = true
            val outcome = trust.confirm("nas.example.test:8920", prompt())
            assertEquals(TrustOutcome.NotSaved, outcome)
            assertTrue(settings.clearPinCalls >= 1)
        }

    @Test
    fun aCallerWhoGoesAwayWhileItConnectsLeavesNothingStoredAndNoPinInForce() =
        runBlocking {
            val gate = CompletableDeferred<ServerResult<ResolvedServer>>()
            val held = GatedTrust(server, gate)
            val gated = CertificateTrust(held, settings, sessions)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { gated.confirm("nas.example.test:8920", prompt()) }
            job.cancel()
            gate.complete(ServerResult.Success(resolved))
            job.join()
            assertTrue(settings.saved.isEmpty())
            assertEquals(1, settings.clearPinCalls)
        }

    /** A server whose `trust` waits for [gate] and ignores cancellation while it waits, as a stubborn callee would. */
    private class GatedTrust(
        private val fake: FakeMediaServer,
        private val gate: CompletableDeferred<ServerResult<ResolvedServer>>,
    ) : MediaServer by fake {
        override suspend fun trust(
            input: String,
            pin: SpkiPin,
        ): ServerResult<ResolvedServer> = withContext(NonCancellable) { gate.await() }
    }
}
