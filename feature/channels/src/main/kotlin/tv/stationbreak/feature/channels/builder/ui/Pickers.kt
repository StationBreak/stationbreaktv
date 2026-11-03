// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.NarrowRowKind
import tv.stationbreak.feature.channels.builder.PickerRow
import tv.stationbreak.feature.channels.builder.PickerView
import tv.stationbreak.feature.channels.builder.SourceKindTab
import tv.stationbreak.feature.channels.ui.ChannelsPalette

private val TabsWidth = 168.dp

/** The tabs, one under another; the open one is selected, the focused one is ringed. */
@Composable
internal fun SourceTabs(
    ui: BuilderUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(TabsWidth)) {
        SourceKindTab.entries.forEach { tab ->
            val focused = ui.focus == BuilderFocus.Tab(tab)
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawnFocus(focused)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(BuilderTags.tab(tab))
                    .semantics { selected = tab == ui.tab },
            ) {
                val open = tab == ui.tab
                Text(
                    tabName(tab),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (open) ChannelsPalette.text else ChannelsPalette.secondary,
                )
            }
        }
    }
}

/** What a line of the Sources list is. */
private sealed interface SourceLine {
    val key: String

    data object Search : SourceLine {
        override val key = "search"
    }

    data class Note(
        val view: PickerView,
    ) : SourceLine {
        override val key = "note"
    }

    data class Entry(
        val row: PickerRow,
    ) : SourceLine {
        override val key = "entry:${row.key}"
    }

    data object Narrow : SourceLine {
        override val key = "narrow"
    }

    data class NarrowValue(
        val row: NarrowRowKind,
    ) : SourceLine {
        override val key = "narrow:${row.name}"
    }
}

private fun linesOf(ui: BuilderUi): List<SourceLine> =
    buildList {
        if (ui.tab == SourceKindTab.Pick) add(SourceLine.Search)
        when (val view = ui.entries) {
            is PickerView.Entries -> view.rows.forEach { add(SourceLine.Entry(it)) }
            else -> add(SourceLine.Note(view))
        }
        add(SourceLine.Narrow)
        if (ui.narrow != null) NarrowRowKind.entries.forEach { add(SourceLine.NarrowValue(it)) }
    }

private fun SourceLine.isFocus(focus: BuilderFocus?): Boolean =
    when (this) {
        SourceLine.Search -> focus == BuilderFocus.Search
        is SourceLine.Entry -> focus == BuilderFocus.Entry(row.key)
        SourceLine.Narrow -> focus == BuilderFocus.Narrow
        is SourceLine.NarrowValue -> focus == BuilderFocus.NarrowRow(row)
        is SourceLine.Note -> false
    }

/** The open tab's entries with ticks, the search line for titles, and the Narrow down rows after them. */
@Composable
internal fun SourceList(
    ui: BuilderUi,
    typing: Typing,
    modifier: Modifier = Modifier,
) {
    val lines = remember(ui.tab, ui.entries, ui.narrow != null) { linesOf(ui) }
    val focused = lines.indexOfFirst { it.isFocus(ui.focus) }
    FocusedLines(lines, focused, key = { it.key }, modifier = modifier.testTag(BuilderTags.LIST)) { line, isFocused ->
        when (line) {
            SourceLine.Search -> SearchLine(ui.searchText, isFocused, typing)
            is SourceLine.Note -> NoteLine(line.view, ui.tab)
            is SourceLine.Entry -> EntryLine(line.row, isFocused)
            SourceLine.Narrow -> TextLine(stringResource(R.string.builder_narrow_down), isFocused, BuilderTags.NARROW)
            is SourceLine.NarrowValue -> TextLine(narrowText(line.row, ui), isFocused, BuilderTags.narrowRow(line.row))
        }
    }
}

@Composable
private fun NoteLine(
    view: PickerView,
    tab: SourceKindTab,
) {
    val text =
        when (view) {
            PickerView.Loading -> {
                stringResource(R.string.builder_working)
            }

            PickerView.Empty -> {
                stringResource(R.string.builder_list_empty)
            }

            PickerView.Unavailable -> {
                val libraries = tab == SourceKindTab.Libraries
                val text = if (libraries) R.string.builder_libraries_unavailable else R.string.builder_list_unavailable
                stringResource(text)
            }

            is PickerView.Entries -> {
                return
            }
        }
    Text(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag(BuilderTags.NOTE),
        style = MaterialTheme.typography.bodyMedium,
        color = ChannelsPalette.secondary,
    )
}

@Composable
private fun EntryLine(
    row: PickerRow,
    focused: Boolean,
) {
    FocusLine(focused, BuilderTags.entry(row.key), ticked = row.ticked) {
        Row {
            Text(
                if (row.ticked) TICK else UNTICKED,
                Modifier.width(28.dp).clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (row.live) {
                Text(
                    stringResource(R.string.builder_live),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChannelsPalette.secondary,
                )
            }
        }
    }
}

private const val TICK = "✓"
private const val UNTICKED = "○"

@Composable
private fun SearchLine(
    text: String,
    focused: Boolean,
    typing: Typing,
) {
    FocusLine(focused, BuilderTags.SEARCH) {
        if (typing.active && focused) {
            TypedField(text, typing.onType, typing.onDone, BuilderTags.SEARCH_INPUT)
        } else if (text.isEmpty()) {
            Text(
                stringResource(R.string.builder_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = ChannelsPalette.secondary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun narrowText(
    row: NarrowRowKind,
    ui: BuilderUi,
): String {
    val filters = ui.draft.filters
    val any = stringResource(R.string.builder_any)
    return when (row) {
        NarrowRowKind.Genres -> {
            genresText(ui)
        }

        NarrowRowKind.Years -> {
            val from = filters.yearFrom
            val to = filters.yearTo
            val value = if (from != null && to != null) stringResource(R.string.builder_years_range, from, to) else any
            labelled(stringResource(R.string.builder_filter_years), value)
        }

        NarrowRowKind.Runtime -> {
            labelled(
                stringResource(R.string.builder_filter_runtime),
                runtimeText(filters.minMinutes, filters.maxMinutes, any),
            )
        }

        NarrowRowKind.Only -> {
            val value =
                when (filters.only) {
                    OnlyKind.Films -> stringResource(R.string.builder_only_films)
                    OnlyKind.Shows -> stringResource(R.string.builder_only_shows)
                    null -> any
                }
            labelled(stringResource(R.string.builder_filter_only), value)
        }

        NarrowRowKind.Rating -> {
            labelled(stringResource(R.string.builder_filter_rating), filters.maxRating ?: any)
        }

        NarrowRowKind.LeaveOut -> {
            labelled(stringResource(R.string.builder_leave_out), onOff(ui.narrow?.leavingOut == true))
        }
    }
}

@Composable
private fun runtimeText(
    min: Int?,
    max: Int?,
    any: String,
): String =
    when {
        min == null && max == null -> any
        min == null -> stringResource(R.string.builder_runtime_up_to, max ?: 0)
        max == null -> stringResource(R.string.builder_runtime_from, min)
        else -> stringResource(R.string.builder_runtime_range, min, max)
    }

// The genre under the cursor and whether it is ticked; Left and Right move the cursor, Select ticks.
@Composable
private fun genresText(ui: BuilderUi): String {
    val label = stringResource(R.string.builder_filter_genres)
    val view = ui.narrow?.genres
    val rows = (view as? PickerView.Entries)?.rows.orEmpty()
    val at = rows.getOrNull(ui.narrow?.genreCursor ?: 0)
    return when {
        at != null -> labelled(label, stringResource(R.string.builder_labelled_on, at.label, onOff(at.ticked)))
        view == PickerView.Loading -> labelled(label, stringResource(R.string.builder_working))
        view == PickerView.Unavailable -> labelled(label, stringResource(R.string.builder_list_unavailable))
        else -> labelled(label, stringResource(R.string.builder_list_empty))
    }
}
