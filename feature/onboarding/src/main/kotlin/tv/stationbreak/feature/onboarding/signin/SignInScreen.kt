// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.TvTextField
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.FieldError
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.NotEncryptedNotice
import tv.stationbreak.feature.onboarding.server.ServerButton

// Sizes only this screen uses; the rest are the server screen's (one look across onboarding).
private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * The sign-in screen as the app shows it. Back is the platform's: it returns to the server screen with the address
 * kept, except while Quick Connect runs, when it cancels Quick Connect first (one level up).
 *
 * @param onEditAddress go back to the server screen to change the address.
 * @param onMissingServer the server screen's hand-off is gone (process death): go back to the server screen.
 * @param onSignedIn the session was saved. What it hands over is pinned by `SignInViewModelTest`; the library picker
 *   (Unit 4) is the next destination and does not exist yet.
 */
@Composable
fun SignInDestination(
    onEditAddress: () -> Unit,
    onMissingServer: () -> Unit,
    onSignedIn: (SignInResult) -> Unit,
) {
    val viewModel: SignInViewModel = hiltViewModel()
    SignInRoot(viewModel, onEditAddress, onMissingServer, onSignedIn)
}

/** [SignInDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun SignInRoot(
    viewModel: SignInViewModel,
    onEditAddress: () -> Unit,
    onMissingServer: () -> Unit,
    onSignedIn: (SignInResult) -> Unit,
) {
    if (viewModel.missingServer) {
        // Nothing to sign in to: show the dark background (no flash) and go back to where the address is typed.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        LaunchedEffect(viewModel) { onMissingServer() }
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Leaving the screen clears the password and stops Quick Connect.
    DisposableEffect(viewModel) { onDispose { viewModel.onLeave() } }
    LaunchedEffect(viewModel) { viewModel.signedIn.collect { onSignedIn(it) } }
    SignInScreen(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onToggleShowPassword = viewModel::onToggleShowPassword,
        onSignIn = viewModel::onSignIn,
        onQuickConnect = viewModel::onQuickConnect,
        onCancelQuickConnect = viewModel::onCancelQuickConnect,
        onEditAddress = onEditAddress,
    )
}

/** The stateless screen: everything it shows comes from [state], and every action goes back out as a callback. */
@Suppress("LongMethod", "LongParameterList") // One screen's layout and its callbacks; splitting hides the focus flow.
@Composable
internal fun SignInScreen(
    state: SignInUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onSignIn: () -> Unit,
    onQuickConnect: () -> Unit,
    onCancelQuickConnect: () -> Unit,
    onEditAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { SignInFocus() }
    val phase = state.phase
    FocusFollowsPhase(phase, focus)
    BackHandler(enabled = phase is SignInPhase.QuickConnect, onBack = onCancelQuickConnect)

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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
                val quickConnecting = phase as? SignInPhase.QuickConnect
                Text(
                    text =
                        if (quickConnecting == null) {
                            stringResource(R.string.signin_headline, state.serverName)
                        } else {
                            stringResource(R.string.quick_connect_headline)
                        },
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The password is typed here, so a connection the user accepted without encryption says so here too.
                if (state.cleartext) NotEncryptedNotice(SignInTags.NOT_ENCRYPTED)
                if (quickConnecting != null) {
                    QuickConnectPanel(quickConnecting.code, focus.quickConnectCancel, onCancelQuickConnect)
                } else {
                    Form(
                        state,
                        focus,
                        onUsernameChange,
                        onPasswordChange,
                        onToggleShowPassword,
                        onSignIn,
                        onQuickConnect,
                    )
                    Outcome(phase, focus, onEditAddress)
                }
            }
        }
    }
}

@Suppress("LongParameterList") // The form's fields and buttons, each with its callback.
@Composable
private fun Form(
    state: SignInUiState,
    focus: SignInFocus,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onSignIn: () -> Unit,
    onQuickConnect: () -> Unit,
) {
    // Focus order: username, password, Sign in, Quick Connect (spec 05, Unit 2). The Show toggle sits beside the
    // password, reached by Right, so Down never has to pass through it.
    TvTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        hint = stringResource(R.string.signin_username_hint),
        fieldTag = SignInTags.USERNAME,
        inputTag = SignInTags.USERNAME_INPUT,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Next,
        onImeAction = { focus.password.requestFocus() },
        modifier =
            Modifier
                .focusProperties { down = focus.password }
                .focusRequester(focus.username),
    )
    (state.phase as? SignInPhase.InvalidInput)?.let { FieldError(it.copy) }
    PasswordRow(state, focus, onPasswordChange, onToggleShowPassword)
    Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
        ServerButton(
            label = R.string.signin_sign_in,
            onClick = onSignIn,
            modifier =
                Modifier
                    .focusProperties {
                        up = focus.password
                        right = focus.quickConnect
                    }.focusRequester(focus.signIn)
                    .testTag(SignInTags.SIGN_IN),
        )
        ServerButton(
            label = R.string.signin_quick_connect,
            onClick = onQuickConnect,
            modifier =
                Modifier
                    .focusProperties {
                        up = focus.showPassword
                        left = focus.signIn
                    }.focusRequester(focus.quickConnect)
                    .testTag(SignInTags.QUICK_CONNECT),
        )
    }
}

/** The password field with its Show toggle beside it. Done on the keyboard moves focus to Sign in. */
@Composable
private fun PasswordRow(
    state: SignInUiState,
    focus: SignInFocus,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
        TvTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            hint = stringResource(R.string.signin_password_hint),
            fieldTag = SignInTags.PASSWORD,
            inputTag = SignInTags.PASSWORD_INPUT,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            onImeAction = { focus.signIn.requestFocus() },
            masked = !state.showPassword,
            modifier =
                Modifier
                    .weight(1f)
                    .focusProperties {
                        up = focus.username
                        down = focus.signIn
                        right = focus.showPassword
                    }.focusRequester(focus.password),
        )
        ServerButton(
            label = if (state.showPassword) R.string.signin_hide_password else R.string.signin_show_password,
            onClick = onToggleShowPassword,
            modifier =
                Modifier
                    .focusProperties {
                        left = focus.password
                        up = focus.username
                        down = focus.quickConnect
                    }.focusRequester(focus.showPassword)
                    .testTag(SignInTags.SHOW_PASSWORD),
        )
    }
}

/** What the last attempt left behind, below the buttons. */
@Composable
private fun Outcome(
    phase: SignInPhase,
    focus: SignInFocus,
    onEditAddress: () -> Unit,
) {
    when (phase) {
        SignInPhase.SigningIn -> {
            SigningInStatus()
        }

        SignInPhase.SignedIn -> {
            Text(
                text = stringResource(R.string.signin_signed_in),
                modifier = Modifier.testTag(SignInTags.SIGNED_IN),
                fontSize = BodySize,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        is SignInPhase.Failed -> {
            SignInErrorPanel(phase, focus.panelAction, onEditAddress)
        }

        SignInPhase.QuickConnectDisabled -> {
            val copy = ErrorCopyTable.copyFor(MediaServerError.QuickConnectDisabled())
            SignInNotice(stringResource(copy.message), stringResource(copy.details))
        }

        is SignInPhase.QuickConnectEnded -> {
            val message =
                when (phase.reason) {
                    QuickConnectEnd.Expired -> R.string.quick_connect_expired
                    QuickConnectEnd.Denied -> R.string.quick_connect_denied
                }
            SignInNotice(stringResource(message))
        }

        SignInPhase.Idle, is SignInPhase.InvalidInput, is SignInPhase.QuickConnect -> {
            Unit
        }
    }
}

/** The focus targets on the sign-in screen. */
private class SignInFocus {
    val username = FocusRequester()
    val password = FocusRequester()
    val showPassword = FocusRequester()
    val signIn = FocusRequester()
    val quickConnect = FocusRequester()
    val panelAction = FocusRequester()
    val quickConnectCancel = FocusRequester()
}

/**
 * The screen opens with the username focused. After that, focus follows the phase (spec 05, Adorabelle's defaults):
 * Cancel while Quick Connect runs; back on the Quick Connect button after it is cancelled or ends; the password field
 * after a rejected password or when Quick Connect is off; the panel's action on any other error; the username when it
 * is blank.
 */
@Composable
private fun FocusFollowsPhase(
    phase: SignInPhase,
    focus: SignInFocus,
) {
    var shown by remember { mutableStateOf<Class<*>?>(null) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(phase::class.java) {
        val previous = shown
        shown = phase::class.java
        val target = focusTarget(phase, previous, focus) ?: return@LaunchedEffect
        // When the focused node has just left the screen (the form for the Quick Connect panel and back), a focus
        // request alone gives the new node focus that its focus ring doesn't show (seen on API 25 with tv-material).
        // Clearing focus first makes the new node draw its focus treatment.
        if (previous != null) focusManager.clearFocus()
        target.requestFocus()
    }
}

/** Where focus goes for [phase], or null to leave it where it is. [previous] is the phase class before, if any. */
private fun focusTarget(
    phase: SignInPhase,
    previous: Class<*>?,
    focus: SignInFocus,
): FocusRequester? =
    when (phase) {
        SignInPhase.Idle -> {
            when (previous) {
                null -> focus.username
                SignInPhase.QuickConnect::class.java -> focus.quickConnect
                else -> null
            }
        }

        is SignInPhase.InvalidInput -> {
            focus.username
        }

        is SignInPhase.Failed -> {
            if (phase.retypePassword) focus.password else focus.panelAction
        }

        is SignInPhase.QuickConnect -> {
            focus.quickConnectCancel
        }

        SignInPhase.QuickConnectDisabled -> {
            focus.password
        }

        is SignInPhase.QuickConnectEnded -> {
            focus.quickConnect
        }

        SignInPhase.SigningIn, SignInPhase.SignedIn -> {
            null
        }
    }
