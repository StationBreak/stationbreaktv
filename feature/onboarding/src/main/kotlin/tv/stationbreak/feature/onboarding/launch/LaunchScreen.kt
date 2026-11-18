// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.screenBody
import tv.stationbreak.core.ui.screenDetail
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.SetupScaffold
import tv.stationbreak.feature.onboarding.launchCue
import tv.stationbreak.feature.onboarding.safeAreaPadding
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ProgressSpinner
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.detailsText
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion
import tv.stationbreak.feature.onboarding.trust.TrustScreen

private val ContentWidth = 640.dp
private const val TOP_OFFSET = 0.1f

/**
 * Where the app opens (spec 05, Unit 4): a plain progress screen while the stored setup is checked, so onboarding
 * never flashes, then on to wherever [LaunchRouter] decided. A server that can't be reached stays here, with the error
 * and Retry; a changed certificate shows the trust screen of Unit 3 over it.
 *
 * [covered] is true while something the host draws over everything (the opening) is up: routing carries on and
 * navigates as soon as it has a target, so the destination is already working beneath, but this screen is not drawn,
 * so the progress never shows through. [onReady] is called once a destination has been chosen; [onNeedsScreen] when
 * routing has a problem to show here (an error with Retry, or the trust question), so the host lets this screen
 * through at once.
 */
@Suppress("LongParameterList") // One callback for each place launch routing can send the app.
@Composable
fun LaunchDestination(
    onWelcome: () -> Unit,
    onConfirmAddress: () -> Unit,
    onSignIn: () -> Unit,
    onLibraries: () -> Unit,
    onFirstSync: () -> Unit,
    onSetupComplete: () -> Unit,
    onChangeServer: () -> Unit,
    covered: Boolean = false,
    onReady: () -> Unit = {},
    onNeedsScreen: () -> Unit = {},
) {
    val viewModel: LaunchViewModel = hiltViewModel()
    LaunchRoot(
        viewModel,
        onWelcome,
        onConfirmAddress,
        onSignIn,
        onLibraries,
        onFirstSync,
        onSetupComplete,
        onChangeServer,
        covered,
        onReady,
        onNeedsScreen,
    )
}

/** [LaunchDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // One callback for each place launch routing can send the app.
@Composable
internal fun LaunchRoot(
    viewModel: LaunchViewModel,
    onWelcome: () -> Unit,
    onConfirmAddress: () -> Unit,
    onSignIn: () -> Unit,
    onLibraries: () -> Unit,
    onFirstSync: () -> Unit,
    onSetupComplete: () -> Unit,
    onChangeServer: () -> Unit,
    covered: Boolean = false,
    onReady: () -> Unit = {},
    onNeedsScreen: () -> Unit = {},
) {
    LaunchFlow(
        viewModel.state,
        viewModel.targets,
        onRetry = viewModel::onRetry,
        onTrust = viewModel::onTrust,
        onDontTrust = viewModel::onDontTrust,
        onWelcome = onWelcome,
        onConfirmAddress = onConfirmAddress,
        onSignIn = onSignIn,
        onLibraries = onLibraries,
        onFirstSync = onFirstSync,
        onSetupComplete = onSetupComplete,
        onChangeServer = onChangeServer,
        covered = covered,
        onReady = onReady,
        onNeedsScreen = onNeedsScreen,
    )
}

/** [LaunchRoot] on the ViewModel's two streams, so a test can feed them. */
@Suppress("LongParameterList") // One callback for each place launch routing can send the app, and each action.
@Composable
internal fun LaunchFlow(
    phase: StateFlow<LaunchPhase>,
    targets: Flow<LaunchTarget>,
    onRetry: () -> Unit,
    onTrust: () -> Unit,
    onDontTrust: () -> Unit,
    onWelcome: () -> Unit,
    onConfirmAddress: () -> Unit,
    onSignIn: () -> Unit,
    onLibraries: () -> Unit,
    onFirstSync: () -> Unit,
    onSetupComplete: () -> Unit,
    onChangeServer: () -> Unit,
    covered: Boolean,
    onReady: () -> Unit,
    onNeedsScreen: () -> Unit,
) {
    val state by phase.collectAsStateWithLifecycle()
    // Routed: a destination was chosen and navigation has been asked for. This screen is not drawn from then on.
    var routed by remember { mutableStateOf(false) }
    val readyNow = rememberUpdatedState(onReady)
    val needsScreenNow = rememberUpdatedState(onNeedsScreen)
    val problem = state is LaunchPhase.Unavailable || state is LaunchPhase.Trust
    LaunchedEffect(problem) { if (problem) needsScreenNow.value() }
    LaunchedEffect(targets) {
        targets.collect {
            // Routing never waits for [covered]: the destination is ready beneath whatever is drawn over it. The host
            // is told first, directly, so it knows before the navigation below has changed anything.
            routed = true
            readyNow.value()
            when (it) {
                LaunchTarget.Welcome -> onWelcome()
                LaunchTarget.ConfirmAddress -> onConfirmAddress()
                LaunchTarget.SignIn -> onSignIn()
                LaunchTarget.Libraries -> onLibraries()
                LaunchTarget.FirstSync -> onFirstSync()
                LaunchTarget.SetupComplete -> onSetupComplete()
            }
        }
    }
    if (!covered && !routed) {
        LaunchScreen(
            state,
            onRetry = onRetry,
            onTrust = onTrust,
            onDontTrust = onDontTrust,
            onChangeServer = onChangeServer,
        )
    }
}

/** The stateless screen: everything it shows comes from [state], and every action goes back out as a callback. */
@Suppress("LongParameterList") // The state, and one callback for each action the screen offers.
@Composable
internal fun LaunchScreen(
    state: LaunchPhase,
    onRetry: () -> Unit,
    onTrust: () -> Unit,
    onDontTrust: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The trust question is a window of its own over this screen: its keys never pass through here.
    SetupScaffold(launchCue(state), modifier, dozeEnabled = state !is LaunchPhase.Trust) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag(LaunchTags.SCREEN)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(safeAreaPadding(top = maxHeight * TOP_OFFSET)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Gap),
                ) {
                    when (state) {
                        LaunchPhase.Routing, LaunchPhase.Trusting, is LaunchPhase.Trust -> Progress()
                        is LaunchPhase.Unavailable -> Unavailable(state.error, state.copy, onRetry, onChangeServer)
                    }
                }
            }
        }
    }
    if (state is LaunchPhase.Trust) {
        // Its own screen over this one: Don't trust has default focus, and Back is Don't trust (spec 05, Unit 3).
        TrustScreen(prompt = state.prompt, serverAddress = state.input, onTrust = onTrust, onDontTrust = onDontTrust)
    }
}

@Composable
private fun Progress() {
    Row(
        modifier = Modifier.testTag(LaunchTags.PROGRESS).politeLiveRegion(),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressSpinner(contentDescription = stringResource(R.string.launch_progress_description))
        Text(
            text = stringResource(R.string.launch_checking),
            style = MaterialTheme.typography.screenBody,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The plain-language error with Retry, which has default focus, and Change server beside it: that opens the server
 * screen with the stored address filled in. The stored session and pin stay until another server is confirmed.
 */
@Composable
private fun Unavailable(
    error: MediaServerError,
    copy: ErrorCopy,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    val cause = ErrorCopyTable.causeText(error)
    val details = detailsText(copy)
    Text(
        text = stringResource(R.string.launch_unavailable_title),
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Column(
        modifier = Modifier.testTag(LaunchTags.ERROR_PANEL).politeLiveRegion(),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.Top) {
            ProblemIcon()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = messageText(error, copy),
                    style = MaterialTheme.typography.screenBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (cause.isEmpty()) {
                            details
                        } else {
                            stringResource(R.string.error_details_with_cause, details, cause)
                        },
                    style = MaterialTheme.typography.screenDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            ServerButton(
                label = R.string.server_retry,
                onClick = onRetry,
                modifier = Modifier.focusRequester(retryFocus).testTag(LaunchTags.RETRY),
            )
            ServerButton(
                label = R.string.launch_change_server,
                onClick = onChangeServer,
                modifier = Modifier.testTag(LaunchTags.CHANGE_SERVER),
            )
        }
    }
}
