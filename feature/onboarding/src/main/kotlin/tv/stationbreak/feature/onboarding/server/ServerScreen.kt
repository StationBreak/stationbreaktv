// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.dp
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
import tv.stationbreak.feature.onboarding.R

// Sizes only this screen uses.
private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private val FieldHeight = 56.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * The server screen as the app shows it. Back is the platform's: from this root screen it leaves the app, and it
 * cancels the cleartext dialog while that is open.
 */
@Composable
fun ServerDestination(onContinue: (ConnectedServer) -> Unit) {
    val viewModel: ServerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onTest = viewModel::onTest,
        onCancel = viewModel::onCancel,
        onEditAddress = viewModel::onEditAddress,
        onConfirmCleartext = viewModel::onConfirmCleartext,
        onDismissCleartext = viewModel::onDismissCleartext,
        onContinue = onContinue,
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
    onContinue: (ConnectedServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { ServerFocus() }
    val phase = state.phase
    FocusFollowsPhase(phase, focus)

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // A 5% safe area on every side (overscan). The content starts at a fixed height, so the headline and the field
        // don't move when a result appears below them.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        start = maxWidth * OVERSCAN,
                        end = maxWidth * OVERSCAN,
                        top = maxHeight * TOP_OFFSET,
                        bottom = maxHeight * OVERSCAN,
                    ).widthIn(max = ContentWidth)
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            Text(
                text = stringResource(R.string.server_headline),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
                color = MaterialTheme.colorScheme.onSurface,
            )
            AddressField(
                value = state.input,
                onValueChange = onInputChange,
                onDone = { focus.test.requestFocus() },
                fieldFocus = focus.field,
            )
            (phase as? ServerPhase.InvalidInput)?.let { FieldError(it.copy) }
            ServerButton(
                label = R.string.server_test,
                onClick = onTest,
                enabled = phase != ServerPhase.Testing,
                modifier = Modifier.focusRequester(focus.test).testTag(ServerTags.TEST),
            )
            if (phase == ServerPhase.Testing) {
                ServerButton(
                    label = R.string.server_cancel,
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(focus.cancel).testTag(ServerTags.CANCEL),
                )
                TestingStatus()
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
            is ServerPhase.ConfirmCleartext -> Unit
        }
    }
}

private fun focusAfterIdle(
    previous: Class<*>?,
    focus: ServerFocus,
) {
    when (previous) {
        null -> focus.field.requestFocus()
        ServerPhase.Testing::class.java, ServerPhase.ConfirmCleartext::class.java -> focus.test.requestFocus()
        else -> Unit
    }
}

/**
 * The address box. It is focusable like any button; Select starts editing and opens the system keyboard, which never
 * opens by itself. Done on the keyboard ends editing and [onDone] moves focus on to Test.
 */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    fieldFocus: FocusRequester,
) {
    var editing by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            inner.requestFocus()
            keyboard?.show()
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { editing = true },
        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).testTag(ServerTags.FIELD),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                focusedContainerColor = colors.surfaceVariant,
                focusedContentColor = colors.onSurfaceVariant,
                pressedContainerColor = colors.surfaceVariant,
                pressedContentColor = colors.onSurfaceVariant,
            ),
        border = FocusStyle.surfaceBorder(),
        scale = FocusStyle.surfaceScale(),
    ) {
        Column(
            modifier = Modifier.heightIn(min = FieldHeight).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (editing) {
                AddressInput(
                    value = value,
                    onValueChange = onValueChange,
                    inner = inner,
                    onDone = {
                        editing = false
                        onDone()
                    },
                    onLeave = { editing = false },
                )
            } else if (value.isEmpty()) {
                Text(text = stringResource(R.string.server_address_hint), fontSize = BodySize)
            } else {
                Text(text = value, fontSize = BodySize, color = colors.onSurface)
            }
        }
    }
}

/** The editable text, shown only while editing: the URI keyboard, with Done moving on. */
@Composable
private fun AddressInput(
    value: String,
    onValueChange: (String) -> Unit,
    inner: FocusRequester,
    onDone: () -> Unit,
    onLeave: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focusedOnce by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = BodySize, color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(inner)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedOnce = true
                    } else if (focusedOnce) {
                        onLeave()
                    }
                }.testTag(ServerTags.FIELD_INPUT),
    )
}
