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

private const val SPINNER_DELAY_MS = 400L

@Composable
internal fun FieldError(copy: ErrorCopy) {
    Row(
        modifier = Modifier.testTag(ServerTags.FIELD_ERROR),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProblemIcon()
        Text(
            text = stringResource(copy.message),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun TestingStatus() {
    // The status text is there at once; the spinner joins it if the Test takes more than about 400 ms.
    var spinnerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        spinnerVisible = true
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.CenterVertically) {
        if (spinnerVisible) {
            ProgressSpinner(contentDescription = stringResource(R.string.server_progress_description))
        }
        Text(
            text = stringResource(R.string.server_testing),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun ConnectedPanel(
    phase: ServerPhase.Connected,
    continueFocus: FocusRequester,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.server_connected_name, phase.server.server.name),
                fontSize = BodySize,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    stringResource(
                        R.string.server_connected_version,
                        phase.server.server.version
                            .toString(),
                    ),
                fontSize = DetailsSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ServerButton(
            label = R.string.server_continue,
            onClick = onContinue,
            modifier = Modifier.focusRequester(continueFocus).testTag(ServerTags.CONTINUE),
        )
    }
}

/** A plain sentence, a small details line, and one next step (decision 5). Network errors add Edit address. */
@Composable
internal fun ErrorPanel(
    phase: ServerPhase.Failed,
    actionFocus: FocusRequester,
    onRetry: () -> Unit,
    onEditAddress: () -> Unit,
) {
    val copy = phase.copy
    val cause = ErrorCopyTable.causeText(phase.error)
    val details = stringResource(copy.details)
    Column(modifier = Modifier.testTag(ServerTags.ERROR_PANEL), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.Top) {
            ProblemIcon()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = messageText(phase.error, copy),
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
            ActionButton(copy.primary, onRetry, onEditAddress, Modifier.focusRequester(actionFocus))
            copy.secondary?.let { ActionButton(it, onRetry, onEditAddress) }
        }
    }
}

@Composable
private fun ActionButton(
    action: ErrorAction,
    onRetry: () -> Unit,
    onEditAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retry = action == ErrorAction.Retry
    ServerButton(
        label = if (retry) R.string.server_retry else R.string.server_edit_address,
        onClick = if (retry) onRetry else onEditAddress,
        modifier = modifier.testTag(if (retry) ServerTags.RETRY else ServerTags.EDIT_ADDRESS),
    )
}

// stringResource takes the format arguments as a vararg, so the spread is the API's shape.
@Suppress("SpreadOperator")
@Composable
private fun messageText(
    error: MediaServerError,
    copy: ErrorCopy,
): String = stringResource(copy.message, *ErrorCopyTable.messageArgs(error).toTypedArray())
