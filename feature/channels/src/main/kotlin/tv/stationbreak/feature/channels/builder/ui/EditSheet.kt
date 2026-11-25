// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.RulesDescription
import tv.stationbreak.core.domain.lineup.builder.ShownOrder
import tv.stationbreak.core.domain.lineup.builder.SkippedPick
import tv.stationbreak.core.domain.lineup.builder.describe
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.PreviewView
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.ui.ChannelsPalette

// The longest Sources text a row draws before it says how many instead (two lines of the band).
private const val SOURCES_FIT_CHARS = 90

private val ACTION_ROWS =
    setOf(SheetRow.Sources, SheetRow.NarrowDown, SheetRow.Kept, SheetRow.Save, SheetRow.Reshuffle, SheetRow.Delete)

/** What the sheet lists: its rows, with the kept settings listed above the row that drops them. */
private sealed interface SheetLine {
    val key: String

    data class Row(
        val row: SheetRow,
    ) : SheetLine {
        override val key = "row:${row.name}"
    }

    data class Skipped(
        val picks: List<SkippedPick>,
    ) : SheetLine {
        override val key = "skipped"
    }

    data class KeptHeader(
        val from: String?,
    ) : SheetLine {
        override val key = "kept:header"
    }

    /** The Cap row while Family channel is off: drawn dimmed, and not a stop of the focus. */
    data object CapOff : SheetLine {
        override val key = "row:CapOff"
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
        // What is kept is listed above the action that replaces it, or above the Preview when nothing is replaceable.
        val keptAt = if (SheetRow.Kept in ui.sheetRows) SheetRow.Kept else SheetRow.Preview
        ui.sheetRows.forEach { row ->
            if (row == keptAt) {
                val from = ui.draft.kept?.takeIf { ui.draft.keptInForce.isNotEmpty() }
                from?.let { add(SheetLine.KeptHeader(it.fromName)) }
                ui.draft.keptInForce.forEachIndexed { i, item -> add(SheetLine.KeptLine(i, item)) }
            }
            add(SheetLine.Row(row))
            if (row == SheetRow.Family && ui.draft.tier == null) add(SheetLine.CapOff)
            val skipped = ui.preview.skippedPicks()
            val tots = ui.draft.tier == FamilyTier.Tots
            if (row == SheetRow.Preview && tots && skipped.isNotEmpty()) add(SheetLine.Skipped(skipped))
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
    val lines =
        remember(ui.sheetRows, ui.draft.kept, ui.draft.keptInForce, ui.draft.tier, ui.preview) {
            sheetLinesOf(ui)
        }
    val focused = lines.indexOfFirst { it is SheetLine.Row && ui.focus == BuilderFocus.Sheet(it.row) }
    FocusedLines(lines, focused, key = { it.key }, modifier = modifier.fillMaxWidth()) { line, isFocused ->
        when (line) {
            is SheetLine.Row -> {
                SheetRowLine(line.row, ui, typing, isFocused)
            }

            SheetLine.CapOff -> {
                CapLine(ui.draft, focused = false, tag = BuilderTags.sheetRow(SheetRow.Cap))
            }

            is SheetLine.Skipped -> {
                NoteText(skippedText(line.picks), BuilderTags.SKIPPED)
            }

            is SheetLine.KeptHeader -> {
                NoteText(keptFromText(line.from), BuilderTags.SHEET_KEPT_FROM)
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

        SheetRow.Family -> {
            val on = draft.tier != null
            val text = labelled(stringResource(R.string.builder_family), onOff(on))
            FocusLine(focused, tag, ticked = on, role = Role.Switch, spoken = Spoken(text)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }

        SheetRow.Cap -> {
            CapLine(draft, focused, tag)
        }

        SheetRow.Preview -> {
            PreviewRow(ui, tag)
        }

        else -> {
            TextLine(sheetText(row, draft), focused, tag, role = Role.Button.takeIf { row in ACTION_ROWS })
        }
    }
}

@Composable
private fun sheetText(
    row: SheetRow,
    draft: ChannelDraft,
): String {
    // What the rows say about the rules is the domain's one description, which a channel's properties word too.
    val description = draft.describe()
    return when (row) {
        SheetRow.Sources -> {
            labelled(stringResource(R.string.builder_row_sources), sourcesText(description))
        }

        SheetRow.NarrowDown -> {
            stringResource(R.string.builder_narrow_down)
        }

        SheetRow.PlayOrder -> {
            labelled(stringResource(R.string.builder_row_play_order), orderValue(description))
        }

        SheetRow.SeriesCap -> {
            labelled(
                stringResource(R.string.builder_series_cap),
                capText(description.seriesCap),
            )
        }

        SheetRow.FilmCap -> {
            labelled(
                stringResource(R.string.builder_film_cap),
                capText(description.filmCap),
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

        SheetRow.Name, SheetRow.Family, SheetRow.Cap, SheetRow.Preview -> {
            ""
        }
    }
}

// The labels, or a count when they would not fit the two lines a row has.
@Composable
private fun sourcesText(description: RulesDescription): String {
    val joined = joined(description.sources.map { it.label }, R.string.builder_join)
    val count = description.sources.size
    if (joined.length <= SOURCES_FIT_CHARS) return joined
    return pluralStringResource(R.plurals.builder_sources_count, count, count)
}

// A kept order has no PlayOrder to name: its own words stand in, as on the Play page.
@Composable
private fun orderValue(description: RulesDescription): String =
    when (val order = description.order) {
        is ShownOrder.Play -> orderName(order.order)
        is ShownOrder.Kept -> keptText(KeptItem.Order(order.mode))
    }

@Composable
private fun NameLine(
    name: String,
    typing: Typing,
    focused: Boolean,
    tag: String,
) {
    val label = stringResource(R.string.builder_row_name)
    val said = if (name.isEmpty()) label else labelled(label, name)
    FocusLine(focused, tag, spoken = Spoken(said).takeUnless { typing.active && focused }) {
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

// What the channel would air next, as the steps' footer words it. It has no action, so the D-pad never stops on it.
@Composable
private fun PreviewRow(
    ui: BuilderUi,
    tag: String,
) {
    // A ready preview with nothing to list yet still has its count; only NothingFits says nothing fits.
    val preview = ui.preview
    val text =
        if (preview is PreviewView.Ready && preview.next.isEmpty()) {
            barCountText(preview.itemCount, preview)
        } else {
            previewText(preview)
        }
    FocusLine(false, tag) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ChannelsPalette.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
