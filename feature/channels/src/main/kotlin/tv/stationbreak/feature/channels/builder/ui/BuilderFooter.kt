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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.hasPool
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderConfirm
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.CAP_RANGE
import tv.stationbreak.feature.channels.builder.NameRowKind
import tv.stationbreak.feature.channels.builder.PlayRowKind
import tv.stationbreak.feature.channels.builder.PreviewView
import tv.stationbreak.feature.channels.builder.playRowsOf
import tv.stationbreak.feature.channels.ui.ChannelsPalette
import tv.stationbreak.feature.channels.ui.channelsKey

private const val PREVIEW_ITEMS = 3

/** The count that fits, what would air next, and (on the first two pages) Next. */
@Composable
internal fun Footer(ui: BuilderUi) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (ui.draft.hasPool()) {
            val count = ui.barCount
            if (count != null) NoteText(stringResource(R.string.builder_bar_count, count), BuilderTags.BAR)
            PreviewLine(ui.preview, ui.draft.kids)
        }
        if (ui.step != BuilderStep.Name) NextButton(ui)
    }
}

@Composable
private fun PreviewLine(
    preview: PreviewView,
    kids: Boolean,
) {
    val skipped =
        when (preview) {
            is PreviewView.Ready -> preview.skipped
            is PreviewView.NothingFits -> preview.skipped
            else -> emptyList()
        }
    val text =
        when (preview) {
            PreviewView.Working -> {
                stringResource(R.string.builder_working)
            }

            is PreviewView.Ready -> {
                val items = preview.next.take(PREVIEW_ITEMS).map { previewItemText(it) }
                stringResource(R.string.builder_preview_next, items.joinToString("  ·  "))
            }

            is PreviewView.NothingFits -> {
                stringResource(R.string.builder_preview_empty)
            }

            PreviewView.Unavailable -> {
                stringResource(R.string.builder_preview_unavailable)
            }
        }
    NoteText(text, BuilderTags.PREVIEW)
    if (kids && skipped.isNotEmpty()) NoteText(skippedText(skipped), BuilderTags.SKIPPED)
}

@Composable
private fun NextButton(ui: BuilderUi) {
    val enabled = ui.nextEnabled
    Text(
        stringResource(R.string.builder_next),
        Modifier
            .fillMaxWidth()
            .drawnFocus(ui.focus == BuilderFocus.Next)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(BuilderTags.NEXT)
            .semantics {
                selected = ui.focus == BuilderFocus.Next
                if (!enabled) disabled()
            },
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) ChannelsPalette.text else ChannelsPalette.dimmed,
    )
}

@Composable
internal fun NoteText(
    text: String,
    tag: String?,
) {
    val base = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    Text(
        text,
        if (tag != null) base.testTag(tag) else base,
        style = MaterialTheme.typography.bodyMedium,
        color = ChannelsPalette.secondary,
    )
}
