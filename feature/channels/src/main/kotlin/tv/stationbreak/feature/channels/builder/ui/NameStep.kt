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
            "${stringResource(R.string.builder_field_number)}: $number",
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
    val sources = draft.sources.joinToString(", ") { it.label }
    val order = draft.order ?: PlayOrder.Shuffle
    val kids = if (draft.kids) ", ${stringResource(R.string.builder_kids)}" else ""
    return "${stringResource(R.string.builder_summary_sources, sources)}. ${orderName(order)}$kids"
}
