// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.NameRowKind
import tv.stationbreak.feature.channels.ui.ChannelsPalette

@Composable
internal fun NameStep(
    ui: BuilderUi,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    val draft = ui.draft
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val onName = ui.focus == BuilderFocus.NameRow(NameRowKind.Name)
        FocusLine(onName, BuilderTags.nameRow(NameRowKind.Name)) {
            if (typing.active && onName) {
                TypedField(draft.name, typing.onType, typing.onDone, BuilderTags.NAME_INPUT)
            } else if (draft.name.isEmpty()) {
                Text(
                    stringResource(R.string.builder_field_name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ChannelsPalette.secondary,
                )
            } else {
                Text(draft.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val number = draft.number?.toString().orEmpty()
        TextLine(
            labelled(stringResource(R.string.builder_field_number), number),
            ui.focus == BuilderFocus.NameRow(NameRowKind.Number),
            BuilderTags.nameRow(NameRowKind.Number),
        )
        val holder = ui.numberHolder
        val held = draft.number
        if (holder != null && held != null) {
            NoteText(stringResource(R.string.builder_number_held, held, holder), BuilderTags.NUMBER_HELD)
        }
        TextLine(
            stringResource(R.string.builder_save_and_watch),
            ui.focus == BuilderFocus.NameRow(NameRowKind.Save),
            BuilderTags.nameRow(NameRowKind.Save),
        )
        NoteText(summaryOf(draft), BuilderTags.SUMMARY)
    }
}

@Composable
private fun summaryOf(draft: ChannelDraft): String {
    val sources = joined(draft.sources.map { it.label }, R.string.builder_join)
    val order = orderName(draft.order ?: PlayOrder.Shuffle)
    val kids = stringResource(R.string.builder_kids)
    val play = if (draft.kids) stringResource(R.string.builder_summary_play, order, kids) else order
    return stringResource(R.string.builder_summary, stringResource(R.string.builder_summary_sources, sources), play)
}
