// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.DetailsSize
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ProgressSpinner
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion

private const val SPINNER_DELAY_MS = 400L
private val CodeSize = 72.sp
private val CodeSpacing = 8.sp

/** "Signing in…", with the spinner joining after about 400 ms. */
@Composable
internal fun SigningInStatus() {
    var spinnerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        spinnerVisible = true
    }
    Row(
        modifier = Modifier.testTag(SignInTags.SIGNING_IN).politeLiveRegion(),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinnerVisible) {
            ProgressSpinner(contentDescription = stringResource(R.string.signin_progress_description))
        }
        Text(
            text = stringResource(R.string.signin_signing_in),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Signed in." Hands over to the library picker in Unit 4; until then the form is gone and this is all there is. */
@Composable
internal fun SignedInStatus() {
    Text(
        text = stringResource(R.string.signin_signed_in),
        modifier = Modifier.testTag(SignInTags.SIGNED_IN).politeLiveRegion(),
        fontSize = BodySize,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * A plain sentence, a small details line, and one next step (decision 5). A rejected username or password has no
 * button: the next step is to type the password again, and focus is already there. Any other error offers Edit
 * address, which has focus only when the problem is with the address ([SignInPhase.Failed.focusesPassword]).
 */
@Composable
internal fun SignInErrorPanel(
    phase: SignInPhase.Failed,
    actionFocus: FocusRequester,
    onEditAddress: () -> Unit,
) {
    val cause = ErrorCopyTable.causeText(phase.error)
    val details = stringResource(phase.copy.details)
    Column(
        modifier = Modifier.testTag(SignInTags.ERROR_PANEL).politeLiveRegion(),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.Top) {
            ProblemIcon()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = messageText(phase.error, phase.copy),
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
        if (!phase.retypePassword) {
            ServerButton(
                label = R.string.server_edit_address,
                onClick = onEditAddress,
                modifier = Modifier.focusRequester(actionFocus).testTag(SignInTags.EDIT_ADDRESS),
            )
        }
    }
}

/** A one-line explanation with the Problem icon, for the Quick Connect outcomes that return to the form. */
@Composable
internal fun SignInNotice(
    message: String,
    details: String? = null,
) {
    Row(
        modifier = Modifier.testTag(SignInTags.NOTICE).politeLiveRegion(),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.Top,
    ) {
        ProblemIcon()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = message, fontSize = BodySize, color = MaterialTheme.colorScheme.onSurface)
            if (details != null) {
                Text(text = details, fontSize = DetailsSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The Quick Connect screen: the code in large type, how to approve it, and Cancel. */
@Composable
internal fun QuickConnectPanel(
    code: String?,
    cancelFocus: FocusRequester,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        Text(
            text = stringResource(R.string.quick_connect_instructions),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (code == null) {
            Text(
                text = stringResource(R.string.quick_connect_getting_code),
                fontSize = BodySize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Read out digit by digit, not as one big number.
            val spoken = stringResource(R.string.quick_connect_code_description, code.toList().joinToString(" "))
            Text(
                text = code,
                modifier = Modifier.testTag(SignInTags.QUICK_CONNECT_CODE).semantics { contentDescription = spoken },
                fontSize = CodeSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = CodeSpacing,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.testTag(SignInTags.QUICK_CONNECT_WAITING).politeLiveRegion(),
                horizontalArrangement = Arrangement.spacedBy(TightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The text beside it says it; a description too would be read out twice.
                ProgressSpinner(contentDescription = null)
                Text(
                    text = stringResource(R.string.quick_connect_waiting),
                    fontSize = BodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ServerButton(
            label = R.string.quick_connect_cancel,
            onClick = onCancel,
            modifier = Modifier.focusRequester(cancelFocus).testTag(SignInTags.QUICK_CONNECT_CANCEL),
        )
    }
}
