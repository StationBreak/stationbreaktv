// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.settings.ProChoice
import tv.stationbreak.feature.settings.ProResult
import tv.stationbreak.feature.settings.ProState
import tv.stationbreak.feature.settings.ProViewModel
import tv.stationbreak.feature.settings.R
import tv.stationbreak.core.ui.R as UiR

private val LineGap = 6.dp

/**
 * StationBreak Pro (#99), drawn by its host over what the viewer came from (the guide's Settings sheet, or the Channels
 * screen when a Pro action was chosen), in the sheet's place and with its look. In a build that sells Pro it shows what
 * Pro adds, the store's price, Buy and Restore purchases and how each ended; with Pro owned it says so and offers
 * neither. A build with no store (the GitHub build, always unlocked) shows a Support StationBreak page: the project's
 * address as text. It never opens by itself and nothing prompts for it: a viewer reaches it from the Settings row or by
 * choosing a Pro action.
 *
 * Keys: Up and Down move between Buy and Restore purchases, OK starts the focused one, Back calls [onDone] once and the
 * host closes the screen, returning to where the viewer was.
 */
@Composable
fun ProDestination(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProViewModel = hiltViewModel()
    ProRoot(viewModel, onDone, modifier)
}

/** [ProDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun ProRoot(
    viewModel: ProViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val done by rememberUpdatedState(onDone)
    val actions = remember(viewModel) { ProActions(viewModel) { done() } }
    ProScreen(
        state,
        modifier
            .focusRequester(keys)
            .onKeyEvent { listScreenKey(it, actions::pad) }
            .focusable(),
    )
}

/** What a key does. Back finishes the screen once: a key that follows it (a fast double Back) is ignored. */
internal class ProActions(
    private val vm: ProViewModel,
    private val onDone: () -> Unit,
) {
    private var finished = false

    fun pad(pad: Pad) {
        if (finished) return
        when (pad) {
            Pad.Up -> {
                vm.move(up = true)
            }

            Pad.Down -> {
                vm.move(up = false)
            }

            Pad.Select -> {
                vm.select()
            }

            Pad.Back -> {
                finished = true
                onDone()
            }
        }
    }
}

/** The screen as [state] has it: Pro's page for a store build, the Support page for the others. */
@Composable
internal fun ProScreen(
    state: ProState,
    modifier: Modifier = Modifier,
) {
    val panel = LocalPanelColors.current
    val offer = state.offer
    val title =
        stringResource(if (offer is ProOffer.Support) R.string.settings_support_title else R.string.settings_pro_title)
    LeftSheet(SheetMeasures.Width, modifier.testTag(SettingsTags.PRO), scrim = false) {
        Text(
            title,
            Modifier
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .testTag(SettingsTags.PRO_TITLE)
                .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = panel.text,
            maxLines = 1,
        )
        Column(
            Modifier.padding(horizontal = SheetMeasures.RowTextPadding, vertical = LineGap),
            verticalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            if (offer is ProOffer.Support) SupportLines(offer.url) else StoreLines(state)
        }
        if (state.actionable) {
            Column(verticalArrangement = Arrangement.spacedBy(SheetMeasures.RowGap)) {
                if (state.canBuy) {
                    Answer(
                        stringResource(R.string.settings_pro_buy),
                        SettingsTags.PRO_BUY,
                        focused = state.focus == ProChoice.Buy,
                    )
                }
                Answer(
                    stringResource(R.string.settings_pro_restore),
                    SettingsTags.PRO_RESTORE,
                    focused = state.focus == ProChoice.Restore,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        KeyHintRow(
            proHint(state),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(SettingsTags.PRO_HINT),
        )
    }
}

@Composable
private fun SupportLines(url: String) {
    Line(stringResource(R.string.settings_support_body), SettingsTags.SUPPORT_BODY, SheetMeasures.BodySize)
    // The address as text (a TV has no browser to open it in), in the stronger colour so it reads as the point.
    Text(
        url,
        Modifier.testTag(SettingsTags.SUPPORT_URL),
        style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
        color = LocalPanelColors.current.text,
    )
    Line(stringResource(R.string.settings_support_note), SettingsTags.SUPPORT_NOTE, SheetMeasures.CaptionSize)
}

@Composable
private fun StoreLines(state: ProState) {
    val result = state.result?.let { stringResource(resultText(it)) }
    val status =
        when {
            state.busy -> stringResource(R.string.settings_pro_working)
            state.locked -> stringResource(R.string.settings_pro_kids)
            else -> result
        }
    if (state.owned) {
        Line(stringResource(R.string.settings_pro_owned), SettingsTags.PRO_OWNED, SheetMeasures.BodySize)
    } else {
        Line(stringResource(R.string.settings_pro_intro), SettingsTags.PRO_ADDS, SheetMeasures.BodySize)
        for (line in ADDS) Line(stringResource(line), SettingsTags.PRO_ADDS, SheetMeasures.CaptionSize)
        val price = state.price?.let { stringResource(R.string.settings_pro_price, it) }
        Line(
            price ?: stringResource(R.string.settings_pro_price_unknown),
            SettingsTags.PRO_PRICE,
            SheetMeasures.BodySize,
        )
    }
    if (status != null) {
        // The one live region: what happened after a choice.
        Line(
            status,
            SettingsTags.PRO_STATUS,
            SheetMeasures.BodySize,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private val ADDS =
    listOf(
        R.string.settings_pro_adds_channels,
        R.string.settings_pro_adds_reset,
        R.string.settings_pro_adds_custom,
        R.string.settings_pro_adds_hours,
    )

private fun resultText(result: ProResult): Int =
    when (result) {
        ProResult.Bought -> R.string.settings_pro_bought
        ProResult.AlreadyOwned -> R.string.settings_pro_already_owned
        ProResult.Cancelled -> R.string.settings_pro_cancelled
        ProResult.Pending -> R.string.settings_pro_pending
        ProResult.Confirming -> R.string.settings_pro_confirming
        ProResult.Failed -> R.string.settings_pro_failed
        ProResult.Restored -> R.string.settings_pro_restored
        ProResult.NothingToRestore -> R.string.settings_pro_nothing_to_restore
        ProResult.RestoreFailed -> R.string.settings_pro_restore_failed
    }

// OK chooses while an action can start; Back always leaves.
@Composable
private fun proHint(state: ProState): List<KeyHintPair> {
    val ok = stringResource(UiR.string.hint_key_ok)
    val back = stringResource(UiR.string.hint_key_back)
    val leave = stringResource(UiR.string.hint_close)
    return if (state.actionable && !state.busy) {
        listOf(KeyHintPair(ok, stringResource(UiR.string.hint_choose), round = true), KeyHintPair(back, leave))
    } else {
        listOf(KeyHintPair(back, leave))
    }
}
