// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.PlayRowKind
import tv.stationbreak.feature.channels.builder.playRowsOf
import tv.stationbreak.feature.channels.ui.ChannelsPalette

/** What the Play page lists: the rows the draft shows, and the kept settings with the action that drops them. */
private sealed interface PlayLine {
    val key: String

    data class Row(
        val kind: PlayRowKind,
    ) : PlayLine {
        override val key = "row:${kind.name}"
    }

    data class KeptHeader(
        val from: String,
    ) : PlayLine {
        override val key = "kept:header"
    }

    data class KeptLine(
        val index: Int,
        val item: KeptItem,
    ) : PlayLine {
        override val key = "kept:$index"
    }
}

private fun playLinesOf(draft: ChannelDraft): List<PlayLine> =
    buildList {
        val rows = playRowsOf(draft)

        // What is kept is listed above the action that replaces it, or after the rows when nothing is replaceable.
        fun addKept() {
            draft.kept?.takeIf { draft.keptInForce.isNotEmpty() }?.let { add(PlayLine.KeptHeader(it.fromName)) }
            draft.keptInForce.forEachIndexed { i, item -> add(PlayLine.KeptLine(i, item)) }
        }
        rows.forEach { kind ->
            if (kind == PlayRowKind.Kept) addKept()
            add(PlayLine.Row(kind))
        }
        if (PlayRowKind.Kept !in rows) addKept()
    }

@Composable
internal fun PlayStep(
    ui: BuilderUi,
    modifier: Modifier = Modifier,
) {
    val draft = ui.draft
    val lines = remember(draft) { playLinesOf(draft) }
    val focused = lines.indexOfFirst { it is PlayLine.Row && ui.focus == BuilderFocus.PlayRow(it.kind) }
    FocusedLines(lines, focused, key = { it.key }, modifier = modifier.fillMaxWidth()) { line, isFocused ->
        when (line) {
            is PlayLine.Row -> PlayRow(line.kind, ui, isFocused)
            is PlayLine.KeptHeader -> NoteText(stringResource(R.string.builder_kept_from, line.from), null)
            is PlayLine.KeptLine -> NoteText(keptText(line.item), BuilderTags.kept(line.index))
        }
    }
}

@Composable
private fun PlayRow(
    kind: PlayRowKind,
    ui: BuilderUi,
    focused: Boolean,
) {
    val draft = ui.draft
    val tag = BuilderTags.playRow(kind)
    when (kind) {
        PlayRowKind.Order -> {
            val order = draft.order
            val kept = draft.keptInForce.filterIsInstance<KeptItem.Order>().firstOrNull()
            FocusLine(focused, tag) {
                Column {
                    val name =
                        if (order == null && kept != null) keptText(kept) else orderName(order ?: PlayOrder.Shuffle)
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (order != null || kept == null) {
                        Text(
                            orderHint(order ?: PlayOrder.Shuffle),
                            Modifier.testTag(BuilderTags.ORDER_HINT),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChannelsPalette.secondary,
                        )
                    }
                }
            }
        }

        PlayRowKind.SeriesCap -> {
            val cap = (draft.seriesCap ?: ChannelDraft.DEFAULT_SERIES_CAP).coerceIn(ChannelDraft.CAP_RANGE)
            TextLine(labelled(stringResource(R.string.builder_series_cap), "$cap"), focused, tag)
        }

        PlayRowKind.FilmCap -> {
            val cap = (draft.filmCap ?: ChannelDraft.DEFAULT_FILM_CAP).coerceIn(ChannelDraft.CAP_RANGE)
            TextLine(labelled(stringResource(R.string.builder_film_cap), "$cap"), focused, tag)
        }

        PlayRowKind.Kids -> {
            TextLine(labelled(stringResource(R.string.builder_kids), onOff(draft.kids)), focused, tag)
        }

        PlayRowKind.Kept -> {
            TextLine(stringResource(R.string.builder_use_builder_settings), focused, tag)
        }
    }
}
