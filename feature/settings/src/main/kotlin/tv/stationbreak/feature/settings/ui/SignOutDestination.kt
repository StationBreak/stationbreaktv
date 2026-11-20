// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.feature.settings.R
import tv.stationbreak.feature.settings.SignOutChoice
import tv.stationbreak.feature.settings.SignOutState
import tv.stationbreak.feature.settings.SignOutStep
import tv.stationbreak.feature.settings.SignOutViewModel
import tv.stationbreak.core.ui.R as UiR

// The room between the title, the lines under it and the answers.
private val LineGap = 8.dp

/**
 * The question whether to sign out of this TV (#336), drawn by its host over the guide's Settings sheet, in the sheet's
 * place and with its look (a [LeftSheet] with the shared [SheetMeasures]): "Sign out?", who is signed in, the note
 * that the TV will need a new sign-in, then Stay signed in (which has the focus) and Sign out. The focus is drawn from
 * the state, not Compose's focus search.
 *
 * Keys: Up and Down move between the two, OK chooses, Back stays signed in. Staying calls [onDone] once, and the host
 * closes the question. Once the sign-out has run, [onSignedOut] is called once and the host leaves for the server
 * screen, where a server can be chosen again.
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
    // Each showing asks afresh, on Stay signed in, and puts the focus back there when it closes.
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    LaunchedEffect(viewModel) { viewModel.signedOut.collect { signedOut() } }
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

/**
 * The question as [state] has it. A screen reader is told it by one live region, the root: on Stay signed in (where
 * it opens) the question, the note and the answer; on Sign out the answer alone; once it ran with the server not told,
 * that message.
 */
@Composable
internal fun SignOutScreen(
    state: SignOutState,
    modifier: Modifier = Modifier,
) {
    val panel = LocalPanelColors.current
    val question = stringResource(R.string.settings_sign_out_question)
    val stay = stringResource(R.string.settings_sign_out_stay)
    val busy = state.step == SignOutStep.Busy
    val confirm = stringResource(if (busy) R.string.settings_signing_out else R.string.settings_sign_out)
    val notRevoked = stringResource(R.string.settings_signed_out_not_revoked)
    val note = if (state.step == SignOutStep.NotRevoked) notRevoked else stringResource(R.string.settings_sign_out_note)
    val spoken =
        when {
            state.step == SignOutStep.NotRevoked -> notRevoked
            state.focus == SignOutChoice.Stay -> listOf(question, note, stay).joinToString(" ")
            else -> confirm
        }
    LeftSheet(
        SheetMeasures.Width,
        modifier
            .testTag(SettingsTags.SIGN_OUT)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        scrim = false,
    ) {
        Text(
            question,
            Modifier
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .testTag(SettingsTags.SIGN_OUT_TITLE)
                .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = panel.text,
            maxLines = 1,
        )
        Column(
            Modifier.padding(horizontal = SheetMeasures.RowTextPadding, vertical = LineGap),
            verticalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            state.serverName?.let { SignedInLine(it, SettingsTags.SIGNED_IN) }
            Line(note, SettingsTags.SIGN_OUT_NOTE, SheetMeasures.BodySize)
        }
        if (state.step != SignOutStep.NotRevoked) {
            Column(verticalArrangement = Arrangement.spacedBy(SheetMeasures.RowGap)) {
                Answer(stay, SettingsTags.SIGN_OUT_STAY, focused = state.focus == SignOutChoice.Stay)
                Answer(confirm, SettingsTags.SIGN_OUT_CONFIRM, focused = state.focus == SignOutChoice.SignOut)
            }
        }
        Spacer(Modifier.weight(1f))
        KeyHintRow(
            signOutHint(state.step),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(SettingsTags.SIGN_OUT_HINT),
        )
    }
}

/** "Signed in to …": the server's name, as the question and the Libraries screen both say it. */
@Composable
internal fun SignedInLine(
    serverName: String,
    tag: String,
) = Line(stringResource(R.string.settings_signed_in_to, serverName), tag, SheetMeasures.CaptionSize)

@Composable
private fun Line(
    text: String,
    tag: String,
    size: TextUnit,
) {
    Text(
        text,
        Modifier.testTag(tag),
        style = guideProse(size, FontWeight.Normal),
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
            .heightIn(min = SheetMeasures.RowMinHeight)
            .panelFocus(focused)
            .padding(horizontal = SheetMeasures.RowTextPadding, vertical = SheetMeasures.RowVerticalPadding)
            .wrapContentHeight(Alignment.CenterVertically)
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                selected = focused
            },
        style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
        color = LocalPanelColors.current.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// What OK and Back do at [step]: choose or stay while it asks, nothing while it runs, go on once it ran.
@Composable
private fun signOutHint(step: SignOutStep): List<KeyHintPair> {
    val ok = stringResource(UiR.string.hint_key_ok)
    val back = stringResource(UiR.string.hint_key_back)
    return when (step) {
        SignOutStep.Asking -> {
            listOf(
                KeyHintPair(ok, stringResource(UiR.string.hint_choose), round = true),
                KeyHintPair(back, stringResource(R.string.settings_sign_out_stay)),
            )
        }

        SignOutStep.Busy -> {
            emptyList()
        }

        SignOutStep.NotRevoked -> {
            val goOn = stringResource(R.string.settings_hint_go_on)
            listOf(KeyHintPair(ok, goOn, round = true), KeyHintPair(back, goOn))
        }
    }
}
