// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.launch.LaunchPhase
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.ServerPhase
import tv.stationbreak.feature.onboarding.signin.QuickConnectEnd
import tv.stationbreak.feature.onboarding.signin.SignInPhase
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.TrustReason

/** Each setup phase's mood and line for Stan: the spec's mapping table (2026-11-18), errors as a shrug. */
class StanCuesTest {
    private val address = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val resolved = ResolvedServer(address, ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0)))
    private val evidence =
        CertificateEvidence(
            spki = SpkiPin(ByteArray(SpkiPin.SIZE)),
            selfSigned = true,
            notBeforeMillis = 0L,
            notAfterMillis = 1L,
        )
    private val prompt = TrustPrompt(TrustReason.Untrusted, evidence)
    private val error = MediaServerError.Timeout()
    private val copy = ErrorCopyTable.copyFor(error)
    private val trust = StanCue(StanMood.Idle, R.string.stan_trust)

    @Test
    fun welcomeGreets() {
        assertEquals(StanCue(StanMood.Idle, R.string.stan_welcome), welcomeCue())
    }

    @Test
    fun theServerScreenFollowsItsPhase() {
        val expected =
            mapOf(
                ServerPhase.Idle to StanCue(StanMood.Idle, R.string.stan_server_idle),
                ServerPhase.Testing to StanCue(StanMood.Busy, R.string.stan_server_testing),
                ServerPhase.Connected(resolved, acknowledgement = null) to
                    StanCue(StanMood.Pleased, R.string.stan_server_found),
                ServerPhase.Failed(error, copy) to StanCue(StanMood.Shrug, R.string.stan_server_failed),
                ServerPhase.InvalidInput(copy) to StanCue(StanMood.Shrug, R.string.stan_server_failed),
                ServerPhase.NotTrusted to trust,
                ServerPhase.ConfirmCleartext(resolved) to trust,
                ServerPhase.TrustRequired("https://jellyfin.example.test", prompt) to trust,
            )
        for ((phase, cue) in expected) assertEquals(phase.toString(), cue, serverCue(phase))
    }

    @Test
    fun theTrustScreenLeavesItToTheViewer() {
        assertEquals(trust, trustCue())
    }

    @Test
    fun theSignInScreenFollowsItsPhase() {
        val failed = StanCue(StanMood.Shrug, R.string.stan_signin_failed)
        val expected =
            mapOf(
                SignInPhase.Idle to StanCue(StanMood.Idle, R.string.stan_signin_idle),
                SignInPhase.SigningIn to StanCue(StanMood.Busy, R.string.stan_signin_busy),
                SignInPhase.InvalidInput(copy) to failed,
                SignInPhase.Failed(error, copy, retypePassword = true) to failed,
                SignInPhase.QuickConnect(code = null) to StanCue(StanMood.Idle, R.string.stan_quick_connect),
                SignInPhase.QuickConnect(code = "123456") to StanCue(StanMood.Idle, R.string.stan_quick_connect),
                SignInPhase.QuickConnectEnded(QuickConnectEnd.Expired) to failed,
                SignInPhase.QuickConnectEnded(QuickConnectEnd.Denied) to failed,
                // The table has no line for these: a shrug or a smile with no bubble, so Stan says nothing untrue.
                SignInPhase.QuickConnectDisabled to StanCue(StanMood.Shrug, null),
                SignInPhase.SignedIn to StanCue(StanMood.Pleased, null),
                SignInPhase.SessionEnded(copy) to StanCue(StanMood.Idle, R.string.stan_signin_idle),
            )
        for ((phase, cue) in expected) assertEquals(phase.toString(), cue, signInCue(phase))
    }

    @Test
    fun launchShowsStanOnlyWithSomethingToSay() {
        assertNull(launchCue(LaunchPhase.Routing))
        assertNull(launchCue(LaunchPhase.Trusting))
        assertEquals(
            StanCue(StanMood.Shrug, R.string.stan_launch_unavailable),
            launchCue(LaunchPhase.Unavailable(error, copy)),
        )
        assertEquals(trust, launchCue(LaunchPhase.Trust("https://jellyfin.example.test", prompt, error, address)))
    }
}
