// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.CAP_RANGE
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.ui.ChannelsPalette

/** What the sheet lists: its rows, with the kept settings listed above the row that drops them. */
private sealed interface SheetLine {
    val key: String

    data class Row(
        val row: SheetRow,
    ) : SheetLine {
        override val key = "row:${row.name}"
    }

    data class KeptHeader(
        val from: String,
    ) : SheetLine {
        override val key = "kept:header"
    }

    data class KeptLine(
        val index: Int,
        val item: KeptItem,
    ) : SheetLine {
        override val key = "kept:$index"
    }
}

private fun sheetLinesOf(ui: BuilderUi): List<SheetLine> =
    buildList {
        ui.sheetRows.forEach { row ->
            if (row == SheetRow.Kept) {
                ui.draft.kept?.let { add(SheetLine.KeptHeader(it.fromName)) }
                ui.draft.keptInForce.forEachIndexed { i, item -> add(SheetLine.KeptLine(i, item)) }
            }
            add(SheetLine.Row(row))
        }
    }

/**
 * The one-sheet editor of a custom channel: the rows of [BuilderUi.sheetRows], in the ViewModel's order. The sheet only
 * draws them; OK on a row is the ViewModel's, and it opens the same picker or panel the steps use. Left and Right turn
 * the value rows (play order, caps, number) in place.
 */
@Composable
internal fun EditSheet(
    ui: BuilderUi,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    val lines = remember(ui.sheetRows, ui.draft.kept, ui.draft.keptInForce) { sheetLinesOf(ui) }
    val focused = lines.indexOfFirst { it is SheetLine.Row && ui.focus == BuilderFocus.Sheet(it.row) }
    FocusedLines(lines, focused, key = { it.key }, modifier = modifier.fillMaxWidth()) { line, isFocused ->
        when (line) {
            is SheetLine.Row -> {
                SheetRowLine(line.row, ui, typing, isFocused)
            }

            is SheetLine.KeptHeader -> {
                NoteText(stringResource(R.string.builder_kept_from, line.from), BuilderTags.SHEET_KEPT_FROM)
            }

            is SheetLine.KeptLine -> {
                NoteText(keptText(line.item), BuilderTags.kept(line.index))
            }
        }
    }
}

@Composable
private fun SheetRowLine(
    row: SheetRow,
    ui: BuilderUi,
    typing: Typing,
    focused: Boolean,
) {
    val tag = BuilderTags.sheetRow(row)
    val draft = ui.draft
    when (row) {
        SheetRow.Name -> {
            NameLine(draft.name, typing, focused, tag)
        }

        SheetRow.Kids -> {
            val text = labelled(stringResource(R.string.builder_kids), onOff(draft.kids))
            ToggleLine(text, draft.kids, focused, tag)
        }

        SheetRow.Preview -> {
            PreviewRow(ui, focused, tag)
        }

        else -> {
            TextLine(sheetText(row, draft), focused, tag)
        }
    }
}

@Composable
private fun sheetText(
    row: SheetRow,
    draft: ChannelDraft,
): String =
    when (row) {
        SheetRow.Sources -> {
            labelled(
                stringResource(R.string.builder_row_sources),
                joined(draft.sources.map { it.label }, R.string.builder_join),
            )
        }

        SheetRow.NarrowDown -> {
            stringResource(R.string.builder_narrow_down)
        }

        SheetRow.PlayOrder -> {
            labelled(stringResource(R.string.builder_row_play_order), orderValue(draft))
        }

        SheetRow.SeriesCap -> {
            labelled(
                stringResource(R.string.builder_series_cap),
                "${(draft.seriesCap ?: ChannelDraft.DEFAULT_SERIES_CAP).coerceIn(CAP_RANGE)}",
            )
        }

        SheetRow.FilmCap -> {
            labelled(
                stringResource(R.string.builder_film_cap),
                "${(draft.filmCap ?: ChannelDraft.DEFAULT_FILM_CAP).coerceIn(CAP_RANGE)}",
            )
        }

        SheetRow.Number -> {
            labelled(stringResource(R.string.builder_row_number), draft.number?.toString().orEmpty())
        }

        SheetRow.Kept -> {
            stringResource(R.string.builder_use_builder_settings)
        }

        SheetRow.Save -> {
            stringResource(R.string.builder_save)
        }

        SheetRow.Reshuffle -> {
            stringResource(R.string.builder_reshuffle)
        }

        SheetRow.Delete -> {
            stringResource(R.string.builder_delete)
        }

        SheetRow.Name, SheetRow.Kids, SheetRow.Preview -> {
            ""
        }
    }

// A kept order has no PlayOrder to name: its own words stand in, as on the Play page.
@Composable
private fun orderValue(draft: ChannelDraft): String {
    val kept = draft.keptInForce.filterIsInstance<KeptItem.Order>().firstOrNull()
    return draft.order?.let { orderName(it) } ?: kept?.let { keptText(it) } ?: orderName(PlayOrder.Shuffle)
}

@Composable
private fun NameLine(
    name: String,
    typing: Typing,
    focused: Boolean,
    tag: String,
) {
    FocusLine(focused, tag) {
        if (typing.active && focused) {
            TypedField(name, typing.onType, typing.onDone, BuilderTags.NAME_INPUT)
        } else if (name.isEmpty()) {
            Text(
                stringResource(R.string.builder_row_name),
                style = MaterialTheme.typography.bodyLarge,
                color = ChannelsPalette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                labelled(stringResource(R.string.builder_row_name), name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleLine(
    text: String,
    on: Boolean,
    focused: Boolean,
    tag: String,
) {
    FocusLine(focused, tag, ticked = on) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

// What the channel would air next, as the steps' footer words it; the row has no action.
@Composable
private fun PreviewRow(
    ui: BuilderUi,
    focused: Boolean,
    tag: String,
) {
    val text = previewText(ui.preview).ifEmpty { stringResource(R.string.builder_preview_empty) }
    FocusLine(focused, tag, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ChannelsPalette.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
