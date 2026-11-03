// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.hasPool
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.PreviewView
import tv.stationbreak.feature.channels.ui.ChannelsPalette

private const val PREVIEW_ITEMS = 3
private const val NOTE_LINES = 2

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
                val line = joined(items, R.string.builder_join_dot)
                if (items.isEmpty()) "" else stringResource(R.string.builder_preview_next, line)
            }

            is PreviewView.NothingFits -> {
                stringResource(R.string.builder_preview_empty)
            }

            PreviewView.Unavailable -> {
                stringResource(R.string.builder_preview_unavailable)
            }
        }
    if (text.isNotEmpty()) NoteText(text, BuilderTags.PREVIEW, live = true)
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
    live: Boolean = false,
) {
    var modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    if (tag != null) modifier = modifier.testTag(tag)
    // Said by a screen reader when it changes: the focus is the ViewModel's, so nothing else announces it.
    if (live) modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = ChannelsPalette.secondary,
        maxLines = NOTE_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}
