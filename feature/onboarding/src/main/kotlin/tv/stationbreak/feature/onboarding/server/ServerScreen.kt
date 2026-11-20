// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.HINT_ALPHA
import tv.stationbreak.core.ui.screenBody
import tv.stationbreak.feature.onboarding.FieldKeyboard
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.RemoteAppTip
import tv.stationbreak.feature.onboarding.SetupColumn
import tv.stationbreak.feature.onboarding.SetupScaffold
import tv.stationbreak.feature.onboarding.StepHeadline
import tv.stationbreak.feature.onboarding.TvTextField
import tv.stationbreak.feature.onboarding.trust.TrustScreen

// Sizes only this screen uses.
private const val TOP_OFFSET = 0.1f

/**
 * The server screen as the app shows it. Back is the platform's: from this root screen it leaves the app, and it
 * cancels the cleartext dialog while that is open.
 */
@Composable
fun ServerDestination(
    onContinue: () -> Unit,
    showStep: Boolean = false,
) {
    val viewModel: ServerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerScreen(
        state = state,
        showStep = showStep,
        onInputChange = viewModel::onInputChange,
        onTest = viewModel::onTest,
        onCancel = viewModel::onCancel,
        onEditAddress = viewModel::onEditAddress,
        onConfirmCleartext = viewModel::onConfirmCleartext,
        onDismissCleartext = viewModel::onDismissCleartext,
        onTrustCertificate = viewModel::onTrustCertificate,
        onDontTrust = viewModel::onDontTrust,
        onContinue = { connected ->
            // The server goes to sign-in in memory (see ServerHandOff), never through the route or saved state.
            viewModel.onContinue(connected)
            onContinue()
        },
    )
}

/** The stateless screen: everything it shows comes from [state], and every action goes back out as a callback. */
@Suppress("LongMethod", "LongParameterList") // One screen's layout and its callbacks; splitting hides the focus flow.
@Composable
internal fun ServerScreen(
    state: ServerUiState,
    onInputChange: (String) -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onEditAddress: () -> Unit,
    onConfirmCleartext: () -> Unit,
    onDismissCleartext: () -> Unit,
    onTrustCertificate: () -> Unit,
    onDontTrust: () -> Unit,
    onContinue: (ConnectedServer) -> Unit,
    modifier: Modifier = Modifier,
    showStep: Boolean = false,
) {
    val focus = remember { ServerFocus() }
    val phase = state.phase
    FocusFollowsPhase(phase, focus)
    val runTest = {
        // A blank field gives the same state again, which StateFlow drops, so the field would not get focus back from
        // the state alone. Take it here.
        if (state.input.isBlank()) focus.field.requestFocus()
        onTest()
    }

    SetupScaffold(serverCue(phase), modifier, covered = serverCovered(phase)) { contentPadding ->
        // The safe area (overscan), Stan's column taking the end side. The content starts at a fixed height, so
        // the headline and the field don't move when a result appears below them.
        // The page scrolls if a large font or a long error doesn't fit; the padding is inside the scroll area.
        SetupColumn(contentPadding, TOP_OFFSET) {
            StepHeadline(
                headline =
                    stringResource(R.string.server_headline, stringResource(R.string.server_product_name)),
                step = if (showStep) 1 else null,
            )
            Text(
                text = stringResource(R.string.server_help, stringResource(R.string.server_product_name)),
                style = MaterialTheme.typography.screenBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RemoteAppTip(ServerTags.TIP)
            AddressField(
                value = state.input,
                onValueChange = onInputChange,
                onGo = runTest,
                fieldFocus = focus.field,
                error = (phase as? ServerPhase.InvalidInput)?.let { stringResource(it.copy.message) },
            )
            (phase as? ServerPhase.InvalidInput)?.let { FieldError(it.copy) }
            ServerButton(
                label = R.string.server_test,
                onClick = runTest,
                enabled = phase != ServerPhase.Testing,
                modifier = Modifier.focusRequester(focus.test).testTag(ServerTags.TEST),
            )
            if (phase == ServerPhase.NotTrusted) NotTrustedNotice()
            if (phase == ServerPhase.Testing) {
                TestingStatus()
                ServerButton(
                    label = R.string.server_cancel,
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(focus.cancel).testTag(ServerTags.CANCEL),
                )
            }
            if (phase is ServerPhase.Connected) {
                ConnectedPanel(phase, focus.continueButton) {
                    onContinue(ConnectedServer(phase.server, phase.acknowledgement))
                }
            }
            if (phase is ServerPhase.Failed) {
                ErrorPanel(
                    phase = phase,
                    actionFocus = focus.action,
                    onRetry = onTest,
                    onEditAddress = {
                        onEditAddress()
                        focus.field.requestFocus()
                    },
                )
            }
        }
    }

    if (phase is ServerPhase.ConfirmCleartext) {
        CleartextDialog(
            byFallback = phase.server.resolvedByFallback,
            onConfirm = onConfirmCleartext,
            onCancel = onDismissCleartext,
        )
    }
    if (phase is ServerPhase.TrustRequired) {
        // Its own screen over this one: Don't trust has default focus, and Back is Don't trust (spec 05, Unit 3).
        TrustScreen(
            prompt = phase.prompt,
            serverAddress = phase.input,
            onTrust = onTrustCertificate,
            onDontTrust = onDontTrust,
        )
    }
}

/** The focus targets on the server screen. */
private class ServerFocus {
    val field = FocusRequester()
    val test = FocusRequester()
    val cancel = FocusRequester()
    val continueButton = FocusRequester()
    val action = FocusRequester()
}

/**
 * The screen opens with the field focused. After that, focus follows the phase: Cancel while testing, Continue on
 * success, the next-step action on an error (spec 05, Adorabelle's defaults for the gaps). Cancelling a Test or the
 * cleartext dialog lands on Test. Leaving an error or a success (editing the address, Edit address) is the user's own
 * move, so focus is left alone.
 */
@Composable
private fun FocusFollowsPhase(
    phase: ServerPhase,
    focus: ServerFocus,
) {
    var shown by remember { mutableStateOf<Class<*>?>(null) }
    LaunchedEffect(phase::class.java) {
        val previous = shown
        shown = phase::class.java
        when (phase) {
            ServerPhase.Testing -> focus.cancel.requestFocus()

            is ServerPhase.Connected -> focus.continueButton.requestFocus()

            is ServerPhase.Failed -> focus.action.requestFocus()

            is ServerPhase.InvalidInput -> focus.field.requestFocus()

            ServerPhase.Idle -> focusAfterIdle(previous, focus)

            // The user refused a certificate: focus stays where the dialog was opened from, on Test.
            ServerPhase.NotTrusted -> focus.test.requestFocus()

            is ServerPhase.ConfirmCleartext, is ServerPhase.TrustRequired -> Unit
        }
    }
}

private fun focusAfterIdle(
    previous: Class<*>?,
    focus: ServerFocus,
) {
    when (previous) {
        null -> focus.field.requestFocus()

        ServerPhase.Testing::class.java,
        ServerPhase.ConfirmCleartext::class.java,
        ServerPhase.TrustRequired::class.java,
        -> focus.test.requestFocus()

        else -> Unit
    }
}

/**
 * The address box: the shared remote-friendly field with the URI keyboard. Done on the keyboard ends editing and
 * [onGo] runs Test.
 */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    fieldFocus: FocusRequester,
    error: String?,
) {
    TvTextField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.server_address_label),
        hint = stringResource(R.string.server_address_hint),
        fieldTag = ServerTags.FIELD,
        inputTag = ServerTags.FIELD_INPUT,
        keyboardOptions = FieldKeyboard.Address,
        onImeAction = onGo,
        modifier = Modifier.focusRequester(fieldFocus),
        error = error,
    )
}
