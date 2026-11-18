// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable

/**
 * Launch routing while the host covers it behind the opening: it navigates as soon as it has a target, never waits for
 * the hold to end, says it is ready, and never draws the progress screen while held or once routed.
 */
@RunWith(AndroidJUnit4::class)
class LaunchFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val phase = MutableStateFlow<LaunchPhase>(LaunchPhase.Routing)
    private val targets = Channel<LaunchTarget>(Channel.BUFFERED)
    private val hold = mutableStateOf(true)
    private val welcomes = mutableListOf<Unit>()
    private var ready = 0
    private var needsScreen = 0

    private fun show() {
        compose.setContent {
            StationBreakTheme {
                LaunchFlow(
                    phase,
                    targets.receiveAsFlow(),
                    onRetry = {},
                    onTrust = {},
                    onDontTrust = {},
                    onWelcome = { welcomes += Unit },
                    onConfirmAddress = {},
                    onSignIn = {},
                    onLibraries = {},
                    onFirstSync = {},
                    onSetupComplete = {},
                    onChangeServer = {},
                    covered = hold.value,
                    onReady = { ready++ },
                    onNeedsScreen = { needsScreen++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTargetNavigatesWhileHeldAndAgainOnceTheHoldEnds() {
        show()
        compose.onNodeWithTag(LaunchTags.SCREEN).assertDoesNotExist()
        targets.trySend(LaunchTarget.Welcome)
        compose.waitUntil(TIMEOUT_MS) { welcomes.size == 1 }
        assertEquals(1, ready)
        // The flag flipping true to false later changes nothing: nothing is waiting on it.
        hold.value = false
        compose.waitForIdle()
        targets.trySend(LaunchTarget.Welcome)
        compose.waitUntil(TIMEOUT_MS) { welcomes.size == 2 }
        // Routed: the progress screen is not drawn, held or not.
        compose.onNodeWithTag(LaunchTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun aProblemAsksTheHostToLetTheScreenThrough() {
        show()
        val error = MediaServerError.Timeout()
        phase.value = LaunchPhase.Unavailable(error, ErrorCopyTable.copyFor(error))
        compose.waitUntil(TIMEOUT_MS) { needsScreen == 1 }
        assertEquals(0, ready)
        compose.onNodeWithTag(LaunchTags.SCREEN).assertDoesNotExist()
        hold.value = false
        compose.waitUntil(TIMEOUT_MS) {
            runCatching { compose.onNodeWithTag(LaunchTags.SCREEN).assertExists() }.isSuccess
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
