// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncPhase
import tv.stationbreak.feature.onboarding.firstsync.firstSyncCue
import tv.stationbreak.feature.onboarding.launch.LaunchPhase
import tv.stationbreak.feature.onboarding.launch.launchCovered
import tv.stationbreak.feature.onboarding.launch.launchCue
import tv.stationbreak.feature.onboarding.libraries.LibrariesPhase
import tv.stationbreak.feature.onboarding.libraries.librariesCue
import tv.stationbreak.feature.onboarding.libraries.signedInCue
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.ServerPhase
import tv.stationbreak.feature.onboarding.server.serverCovered
import tv.stationbreak.feature.onboarding.server.serverCue
import tv.stationbreak.feature.onboarding.signin.QuickConnectEnd
import tv.stationbreak.feature.onboarding.signin.SignInPhase
import tv.stationbreak.feature.onboarding.signin.signInCue
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.TrustReason
import tv.stationbreak.feature.onboarding.welcome.welcomeCue

/**
 * Each setup phase's mood and line for Stan: the spec's mapping table (2026-11-18), errors as a shrug. And which phases
 * put a dialog window over their screen, so Stan stands down there (Review Focus 3).
 */
class StanCuesTest {
    private val input = "https://jellyfin.example.test"
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
    private val launchTrust = LaunchPhase.Trust(input, prompt, error, address)

    @Test
    fun welcomeGreets() {
        assertEquals(StanCue(StanMood.Idle, R.string.stan_welcome), welcomeCue)
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
                ServerPhase.NotTrusted to StanCue(StanMood.Shrug, R.string.stan_server_failed),
                ServerPhase.ConfirmCleartext(resolved) to standingBy,
                ServerPhase.TrustRequired(input, prompt) to standingBy,
            )
        for ((phase, cue) in expected) assertEquals(phase.toString(), cue, serverCue(phase))
    }

    @Test
    fun aCoveredPhaseStandsByWithNoLine() {
        assertEquals(StanCue(StanMood.Idle, null), standingBy)
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
        assertEquals(standingBy, launchCue(launchTrust))
    }

    @Test
    fun onlyTheServerScreensDialogPhasesCoverIt() {
        val covered = listOf(ServerPhase.ConfirmCleartext(resolved), ServerPhase.TrustRequired(input, prompt))
        val open =
            listOf(
                ServerPhase.Idle,
                ServerPhase.NotTrusted,
                ServerPhase.Testing,
                ServerPhase.InvalidInput(copy),
                ServerPhase.Failed(error, copy),
                ServerPhase.Connected(resolved, acknowledgement = null),
            )
        for (phase in covered) assertTrue(phase.toString(), serverCovered(phase))
        for (phase in open) assertFalse(phase.toString(), serverCovered(phase))
    }

    @Test
    fun onlyTheTrustQuestionCoversTheLaunchScreen() {
        assertTrue(launchCovered(launchTrust))
        assertFalse(launchCovered(LaunchPhase.Routing))
        assertFalse(launchCovered(LaunchPhase.Trusting))
        assertFalse(launchCovered(LaunchPhase.Unavailable(error, copy)))
    }

    @Test
    fun thePickerCheersAsItOpensThenAsksWhatToShow() {
        assertEquals(StanCue(StanMood.Cheer, R.string.stan_libraries_in), librariesCue(LibrariesPhase.Choosing, true))
        assertEquals(
            StanCue(StanMood.Idle, R.string.stan_libraries_choosing),
            librariesCue(LibrariesPhase.Choosing, justArrived = false),
        )
    }

    @Test
    fun thePickerFollowsItsPhase() {
        val busy = StanCue(StanMood.Busy, R.string.stan_libraries_busy)
        val empty = StanCue(StanMood.Shrug, R.string.stan_libraries_empty)
        val expected =
            mapOf(
                LibrariesPhase.Loading to busy,
                LibrariesPhase.Saving to busy,
                LibrariesPhase.LoadFailed(error, copy) to empty,
                LibrariesPhase.NoLibraries to empty,
                // The list is on screen: "nothing on the shelf" would be untrue, so a shrug with no bubble.
                LibrariesPhase.SaveFailed to StanCue(StanMood.Shrug, null),
            )
        for ((phase, cue) in expected) {
            // Only the picker's first moments cheer: being just arrived changes nothing else.
            assertEquals(phase.toString(), cue, librariesCue(phase, justArrived = false))
            assertEquals(phase.toString(), cue, librariesCue(phase, justArrived = true))
        }
    }

    @Test
    fun signedInIsStillHere() {
        assertEquals(StanCue(StanMood.Idle, R.string.stan_signed_in), signedInCue)
    }

    @Test
    fun theFirstSyncWorksTheAerialThenCheersUsOnTheAir() {
        val busy = StanCue(StanMood.Busy, R.string.stan_first_sync_busy)
        val expected =
            mapOf(
                FirstSyncPhase.Starting to busy,
                FirstSyncPhase.Syncing(copied = 10, total = 100) to busy,
                FirstSyncPhase.WaitingForNetwork to StanCue(StanMood.Shrug, R.string.stan_first_sync_waiting),
                FirstSyncPhase.Failed(error, copy, sessionLost = false) to
                    StanCue(StanMood.Shrug, R.string.stan_first_sync_failed),
                FirstSyncPhase.Done to StanCue(StanMood.Cheer, R.string.stan_first_sync_done),
            )
        for ((phase, cue) in expected) assertEquals(phase.toString(), cue, firstSyncCue(phase))
    }
}
