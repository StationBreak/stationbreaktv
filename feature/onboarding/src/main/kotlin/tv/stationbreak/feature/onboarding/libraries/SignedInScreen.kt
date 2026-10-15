// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProgressSpinner
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.politeLiveRegion

private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * "Signed in as", reached by Back from the library picker (spec 05, Unit 4). Back from here returns to the picker.
 *
 * @param onContinue back to the picker, signed in as before.
 * @param onSwitchUser the user was signed out: the sign-in form comes back for the same server.
 * @param onMissingSession no sign-in is held (process death): launch routing restores it.
 */
@Composable
fun SignedInDestination(
    onContinue: () -> Unit,
    onSwitchUser: () -> Unit,
    onMissingSession: () -> Unit,
) {
    val viewModel: SignedInViewModel = hiltViewModel()
    SignedInRoot(viewModel, onContinue, onSwitchUser, onMissingSession)
}

/** [SignedInDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun SignedInRoot(
    viewModel: SignedInViewModel,
    onContinue: () -> Unit,
    onSwitchUser: () -> Unit,
    onMissingSession: () -> Unit,
) {
    if (viewModel.missingSession) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        LaunchedEffect(viewModel) { onMissingSession() }
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.switched.collect { onSwitchUser() } }
    SignedInScreen(state, onContinue = onContinue, onSwitchUser = viewModel::onSwitchUser)
}

/** The stateless screen. Continue has default focus: it is the choice that changes nothing. */
@Composable
internal fun SignedInScreen(
    state: SignedInUiState,
    onContinue: () -> Unit,
    onSwitchUser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { continueFocus.requestFocus() }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(SignedInTags.SCREEN),
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
                Text(
                    text =
                        state.name?.let { stringResource(R.string.signed_in_as, it) }
                            ?: stringResource(R.string.signed_in_to, state.serverName),
                    modifier = Modifier.testTag(SignedInTags.HEADLINE),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    ServerButton(
                        label = R.string.signed_in_continue,
                        onClick = onContinue,
                        enabled = !state.switching,
                        emphasis = true,
                        modifier = Modifier.focusRequester(continueFocus).testTag(SignedInTags.CONTINUE),
                    )
                    ServerButton(
                        label = R.string.signed_in_switch_user,
                        onClick = onSwitchUser,
                        enabled = !state.switching,
                        modifier = Modifier.testTag(SignedInTags.SWITCH_USER),
                    )
                }
                if (state.switching) SwitchingStatus()
            }
        }
    }
}

@Composable
private fun SwitchingStatus() {
    Row(
        modifier = Modifier.testTag(SignedInTags.SWITCHING).politeLiveRegion(),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressSpinner(contentDescription = stringResource(R.string.signed_in_progress_description))
        Text(
            text = stringResource(R.string.signed_in_switching),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
