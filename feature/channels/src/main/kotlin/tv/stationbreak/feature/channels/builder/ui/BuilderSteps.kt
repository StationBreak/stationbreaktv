// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderConfirm
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.ui.ChannelsPalette
import tv.stationbreak.feature.channels.ui.channelsKey

private val PanelGap = 16.dp

/**
 * The channel builder (spec 22) drawn over live TV's picture: the three steps in the band left of [pictureHole], and a
 * question (when one is open) below the hole. The focus is drawn from [BuilderUi.focus] (ADR-0008 addendum), not
 * Compose's focus search; one focusable root takes the D-pad and sends it to the ViewModel. The title search and the
 * name are real text fields only while the viewer types into them.
 *
 * The host opens the builder (`openNew`, `openCopy` or `openEdit`) before it shows this. [onEvent] is called once, with
 * how the builder ended: Saved (and whether to watch) or Closed (a Back that leaves).
 */
@Composable
internal fun BuilderRoot(
    viewModel: BuilderViewModel,
    pictureHole: DpRect,
    onEvent: (BuilderEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    var typing by remember { mutableStateOf(false) }
    val typingNow = typing && ui.focus.typesText()
    LaunchedEffect(typingNow) {
        if (!typingNow) {
            typing = false
            keys.requestFocus()
        }
    }
    val exit by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel) {
        // The builder is left once: an event that follows (a fast double Back) is ignored.
        var finished = false
        viewModel.events.collect { event ->
            if (finished) return@collect
            finished = true
            exit(event)
        }
    }
    val actions = remember(viewModel) { BuilderActions(viewModel) { typing = true } }
    BuilderScreen(
        ui = ui,
        pictureHole = pictureHole,
        typing = Typing(typingNow, viewModel::type) { typing = false },
        modifier =
            modifier
                .focusRequester(keys)
                .onKeyEvent { event ->
                    if (typingNow) {
                        // The keyboard has the keys; Back, Up and Down only put it away, so none of them leaves the
                        // builder or the field.
                        val taken = takenWhileTyping(event.key)
                        if (taken && event.type == KeyEventType.KeyUp) typing = false
                        taken
                    } else {
                        channelsKey(event, actions::pad)
                    }
                }.focusable(),
    )
}

@Composable
internal fun BuilderScreen(
    ui: BuilderUi,
    pictureHole: DpRect,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    // Left to right whatever the device's language, as the picture's hole is top-right in screen coordinates.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().testTag(BuilderTags.SCREEN)) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
            val bandWidth = (pictureHole.left - safe.left - PanelGap).coerceAtLeast(0.dp)
            CompositionLocalProvider(LocalContentColor provides ChannelsPalette.text) {
                Column(
                    Modifier
                        .offset(safe.left, safe.top)
                        .width(bandWidth)
                        .height(safe.bottom - safe.top)
                        .background(ChannelsPalette.ground),
                ) {
                    Header(ui)
                    Notices(ui)
                    Body(ui, typing, Modifier.weight(1f))
                }
                val confirm = ui.confirm
                if (confirm != null) {
                    ConfirmPanel(
                        ui,
                        confirm,
                        Modifier
                            .offset(pictureHole.left, pictureHole.bottom + PanelGap)
                            .width(safe.right - pictureHole.left)
                            .background(ChannelsPalette.ground),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(ui: BuilderUi) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        val title =
            if (ui.mode is BuilderMode.New) {
                stringResource(R.string.builder_new_channel)
            } else {
                // A name cleared to blank on the sheet still heads it with the channel's stored name.
                ui.draft.name
                    .ifBlank { ui.storedName }
                    .ifBlank { stringResource(R.string.builder_new_channel) }
            }
        Text(title, Modifier.testTag(BuilderTags.TITLE), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        if (ui.onSheet) {
            Text(
                stringResource(R.string.builder_edit),
                Modifier.testTag(BuilderTags.EDIT),
                style = MaterialTheme.typography.labelLarge,
                color = ChannelsPalette.secondary,
            )
        } else {
            StepStrip(ui)
        }
    }
}

@Composable
private fun StepStrip(ui: BuilderUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BuilderStep.entries.forEach { step ->
            val current = step == ui.step
            Text(
                stepName(step),
                Modifier.testTag(BuilderTags.step(step)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) ChannelsPalette.text else ChannelsPalette.secondary,
            )
        }
    }
}

@Composable
private fun Notices(ui: BuilderUi) {
    val message = ui.message
    val problem = ui.problem
    if (message != null) {
        val text = messageText(message, ui.numberHolder, ui.draft.number)
        NoticeLine(text, BuilderTags.MESSAGE)
    }
    if (problem != null) {
        val where = ui.problemStep?.takeIf { it != ui.step && !ui.onSheet }
        val text = problemText(problem)
        NoticeLine(
            if (where !=
                null
            ) {
                stringResource(R.string.builder_problem_on_step, text, stepName(where))
            } else {
                text
            },
            BuilderTags.PROBLEM,
        )
    }
}

@Composable
private fun NoticeLine(
    text: String,
    tag: String,
) {
    Text(
        text,
        Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = ChannelsPalette.secondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Body(
    ui: BuilderUi,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    // The sheet of an edit is drawn by EditSheet; the steps below are what its rows open.
    if (ui.ready && ui.onSheet) {
        EditSheet(ui, typing, modifier)
    } else if (ui.ready) {
        Column(modifier) {
            when (ui.step) {
                BuilderStep.Sources -> SourcesStep(ui, typing, Modifier.weight(1f))
                BuilderStep.Play -> PlayStep(ui, Modifier.weight(1f))
                BuilderStep.Name -> NameStep(ui, typing, Modifier.weight(1f))
            }
            Footer(ui)
        }
    }
}

@Composable
private fun SourcesStep(
    ui: BuilderUi,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth()) {
        SourceTabs(ui)
        SourceList(ui, typing, Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmPanel(
    ui: BuilderUi,
    confirm: BuilderConfirm,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(BuilderTags.CONFIRM).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            confirmText(confirm),
            Modifier.testTag(BuilderTags.CONFIRM_TEXT).semantics { liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.titleMedium,
        )
        val (action, keep) = confirmChoices(confirm, ui.mode)
        TextLine(action, ui.focus == BuilderFocus.Choice(0), BuilderTags.CONFIRM_YES)
        TextLine(keep, ui.focus == BuilderFocus.Choice(1), BuilderTags.CONFIRM_NO)
    }
}
