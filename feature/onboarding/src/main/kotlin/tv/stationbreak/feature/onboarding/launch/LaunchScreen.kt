// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.DetailsSize
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ProgressSpinner
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion
import tv.stationbreak.feature.onboarding.trust.TrustScreen

private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * Where the app opens (spec 05, Unit 4): a plain progress screen while the stored setup is checked, so onboarding
 * never flashes, then on to wherever [LaunchRouter] decided. A server that can't be reached stays here, with the error
 * and Retry; a changed certificate shows the trust screen of Unit 3 over it.
 */
@Composable
fun LaunchDestination(
    onNoServer: () -> Unit,
    onSignIn: () -> Unit,
    onLibraries: () -> Unit,
    onSetupComplete: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val viewModel: LaunchViewModel = hiltViewModel()
    LaunchRoot(viewModel, onNoServer, onSignIn, onLibraries, onSetupComplete, onChangeServer)
}

/** [LaunchDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // One callback for each place launch routing can send the app.
@Composable
internal fun LaunchRoot(
    viewModel: LaunchViewModel,
    onNoServer: () -> Unit,
    onSignIn: () -> Unit,
    onLibraries: () -> Unit,
    onSetupComplete: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.targets.collect {
            when (it) {
                LaunchTarget.NoServer -> onNoServer()
                LaunchTarget.SignIn -> onSignIn()
                LaunchTarget.Libraries -> onLibraries()
                LaunchTarget.SetupComplete -> onSetupComplete()
            }
        }
    }
    LaunchScreen(
        state,
        onRetry = viewModel::onRetry,
        onTrust = viewModel::onTrust,
        onDontTrust = viewModel::onDontTrust,
        onChangeServer = onChangeServer,
    )
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
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(LaunchTags.SCREEN),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = maxWidth * OVERSCAN,
                        end = maxWidth * OVERSCAN,
                        top = maxHeight * TOP_OFFSET,
                        bottom = maxHeight * OVERSCAN,
                    ),
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
            fontSize = BodySize,
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
    val details = stringResource(copy.details)
    Text(
        text = stringResource(R.string.launch_unavailable_title),
        style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
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
                    fontSize = BodySize,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (cause.isEmpty()) {
                            details
                        } else {
                            stringResource(R.string.error_details_with_cause, details, cause)
                        },
                    fontSize = DetailsSize,
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
