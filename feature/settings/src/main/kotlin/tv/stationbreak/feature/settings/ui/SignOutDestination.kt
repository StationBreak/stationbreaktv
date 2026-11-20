// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.KeyHint
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.StartSheet
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.feature.settings.R
import tv.stationbreak.feature.settings.SignOutChoice
import tv.stationbreak.feature.settings.SignOutState
import tv.stationbreak.feature.settings.SignOutStep
import tv.stationbreak.feature.settings.SignOutViewModel

// The guide's Settings sheet's measures, so the question reads as a step of that sheet.
private val RowMinHeight = 36.dp
private val RowTextPadding = 10.dp
private val RowVerticalPadding = 6.dp
private val RowGap = 4.dp
private val LineGap = 8.dp
private val HintPairGap = 20.dp
private val HintMinHeight = 20.dp
private const val TITLE_SP = 24
private const val BODY_SP = 16
private const val CAPTION_SP = 14
private const val HINT_SPACE = "   "

/**
 * The question whether to sign out of this TV (#336), drawn by its host over the guide's Settings sheet, in the sheet's
 * place and with its look: "Sign out?", who is signed in, the note that the TV will need a new sign-in, then Stay
 * signed in (which has the focus) and Sign out. The focus is drawn from the state, not Compose's focus search.
 *
 * Keys: Up and Down move between the two, OK chooses, Back stays signed in. Staying calls [onDone] once, and the host
 * closes the question. Once the sign-out has run, [onSignedOut] is called and the host leaves for the server screen,
 * where a server can be chosen again.
 */
@Composable
fun SignOutDestination(
    onDone: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SignOutViewModel = hiltViewModel()
    SignOutRoot(viewModel, onDone, onSignedOut, modifier)
}

/** [SignOutDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun SignOutRoot(
    viewModel: SignOutViewModel,
    onDone: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedOut by rememberUpdatedState(onSignedOut)
    // Each showing asks afresh, on Stay signed in.
    LaunchedEffect(viewModel) {
        viewModel.open()
        viewModel.signedOut.collect { signedOut() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val done by rememberUpdatedState(onDone)
    val actions = remember(viewModel) { SignOutActions(viewModel) { done() } }
    SignOutScreen(
        state,
        modifier
            .focusRequester(keys)
            .onKeyEvent { listScreenKey(it, actions::pad) }
            .focusable(),
    )
}

/** What a key does. Staying finishes the question once: a key that follows it (a fast double Back) is ignored. */
internal class SignOutActions(
    private val vm: SignOutViewModel,
    private val onDone: () -> Unit,
) {
    private var finished = false

    fun pad(pad: Pad) {
        if (finished) return
        val stay =
            when (pad) {
                Pad.Up -> {
                    vm.move(up = true)
                    false
                }

                Pad.Down -> {
                    vm.move(up = false)
                    false
                }

                Pad.Select -> {
                    vm.select()
                }

                Pad.Back -> {
                    vm.back()
                }
            }
        if (stay) {
            finished = true
            onDone()
        }
    }
}

/** The question as [state] has it. A screen reader is told the answer under the D-pad as it moves. */
@Composable
internal fun SignOutScreen(
    state: SignOutState,
    modifier: Modifier = Modifier,
) {
    val panel = LocalPanelColors.current
    val stay = stringResource(R.string.settings_sign_out_stay)
    val busy = state.step == SignOutStep.Busy
    val confirm = stringResource(if (busy) R.string.settings_signing_out else R.string.settings_sign_out)
    val notRevoked = stringResource(R.string.settings_signed_out_not_revoked)
    val spoken =
        when {
            state.step == SignOutStep.NotRevoked -> notRevoked
            state.focus == SignOutChoice.Stay -> stay
            else -> confirm
        }
    StartSheet(
        modifier
            .testTag(SettingsTags.SIGN_OUT)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        scrim = false,
    ) {
        Text(
            stringResource(R.string.settings_sign_out_question),
            Modifier
                .padding(horizontal = RowTextPadding)
                .testTag(SettingsTags.SIGN_OUT_TITLE)
                .semantics { heading() },
            style = guideText(TITLE_SP.sp, FontWeight.SemiBold),
            color = panel.text,
            maxLines = 1,
        )
        Column(
            Modifier.padding(horizontal = RowTextPadding, vertical = LineGap),
            verticalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            state.serverName?.let {
                Line(stringResource(R.string.settings_signed_in_to, it), SettingsTags.SIGNED_IN, CAPTION_SP)
            }
            val note = stringResource(R.string.settings_sign_out_note)
            Line(if (state.step == SignOutStep.NotRevoked) notRevoked else note, SettingsTags.SIGN_OUT_NOTE, BODY_SP)
        }
        if (state.step != SignOutStep.NotRevoked) {
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                Answer(stay, SettingsTags.SIGN_OUT_STAY, focused = state.focus == SignOutChoice.Stay)
                Answer(confirm, SettingsTags.SIGN_OUT_CONFIRM, focused = state.focus == SignOutChoice.SignOut)
            }
        }
        Spacer(Modifier.weight(1f))
        Hint(state.step)
    }
}

@Composable
private fun Line(
    text: String,
    tag: String,
    size: Int,
) {
    Text(
        text,
        Modifier.testTag(tag),
        style = guideProse(size.sp, FontWeight.Normal),
        color = LocalPanelColors.current.secondary,
    )
}

// One answer: a row with the guide family's drawn ring while it has the focus, spoken as a button.
@Composable
private fun Answer(
    label: String,
    tag: String,
    focused: Boolean,
) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .panelFocus(focused)
            .padding(horizontal = RowTextPadding, vertical = RowVerticalPadding)
            .wrapContentHeight(Alignment.CenterVertically)
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                selected = focused
            },
        style = guideText(BODY_SP.sp, FontWeight.Medium),
        color = LocalPanelColors.current.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// What OK and Back do, with key caps (DESIGN.md "Key Hints"), read out as one line.
@Composable
private fun Hint(step: SignOutStep) {
    val ok = stringResource(R.string.settings_key_ok)
    val back = stringResource(R.string.settings_key_back)
    val pairs =
        if (step == SignOutStep.NotRevoked) {
            val goOn = stringResource(R.string.settings_hint_go_on)
            listOf(Triple(ok, goOn, true), Triple(back, goOn, false))
        } else {
            listOf(
                Triple(ok, stringResource(R.string.settings_hint_choose), true),
                Triple(back, stringResource(R.string.settings_sign_out_stay), false),
            )
        }
    val spoken = pairs.joinToString(HINT_SPACE) { (key, action) -> "$key $action" }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HintMinHeight)
            .padding(start = RowTextPadding)
            .testTag(SettingsTags.SIGN_OUT_HINT)
            .clearAndSetSemantics { text = AnnotatedString(spoken) },
        horizontalArrangement = Arrangement.spacedBy(HintPairGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((key, action, round) in pairs) KeyHint(key, action, round)
    }
}
