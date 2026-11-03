// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.feature.channels.PanelMove

/** What a D-pad key needs to know of the builder to pick the next focus. Nothing here is Android. */
@Suppress("LongParameterList") // The pieces of the builder a key reads, one for one.
internal class NavView(
    val step: BuilderStep,
    val tab: SourceKindTab,
    val entryKeys: List<String>,
    val narrowOpen: Boolean,
    val playRows: List<PlayRowKind>,
    val sheetRows: List<SheetRow>,
    val nameRows: List<NameRowKind>,
)

private val PanelMove.step: Int get() = if (this == PanelMove.Up || this == PanelMove.Left) -1 else 1

private val PanelMove.vertical: Boolean get() = this == PanelMove.Up || this == PanelMove.Down

/** Where [move] takes the D-pad from [focus]. Left and Right on a value row change the value, not the focus. */
internal fun navigate(
    view: NavView,
    focus: BuilderFocus,
    move: PanelMove,
): BuilderFocus =
    when (focus) {
        is BuilderFocus.Tab -> fromTab(view, focus, move)
        BuilderFocus.Search -> fromSearch(view, move)
        is BuilderFocus.Entry -> fromEntry(view, focus, move)
        BuilderFocus.Narrow -> fromNarrow(view, move)
        is BuilderFocus.NarrowRow -> fromNarrowRow(focus, move)
        BuilderFocus.Next -> fromNext(view, move)
        is BuilderFocus.PlayRow -> inList(view.playRows, focus.row, move, BuilderFocus::PlayRow) ?: onNext(focus, move)
        is BuilderFocus.NameRow -> inList(view.nameRows, focus.row, move, BuilderFocus::NameRow) ?: focus
        is BuilderFocus.Sheet -> inList(view.sheetRows, focus.row, move, BuilderFocus::Sheet) ?: focus
        is BuilderFocus.Choice -> BuilderFocus.Choice((focus.index + move.step).coerceIn(0, 1))
    }

private fun fromTab(
    view: NavView,
    focus: BuilderFocus.Tab,
    move: PanelMove,
): BuilderFocus =
    when {
        move.vertical -> {
            BuilderFocus.Tab(
                SourceKindTab.entries[(focus.tab.ordinal + move.step).coerceIn(0, SourceKindTab.entries.lastIndex)],
            )
        }

        move == PanelMove.Left -> {
            focus
        }

        view.tab == SourceKindTab.Pick -> {
            BuilderFocus.Search
        }

        else -> {
            view.entryKeys.firstOrNull()?.let { BuilderFocus.Entry(it) } ?: BuilderFocus.Narrow
        }
    }

private fun fromSearch(
    view: NavView,
    move: PanelMove,
): BuilderFocus =
    when (move) {
        PanelMove.Down -> view.entryKeys.firstOrNull()?.let { BuilderFocus.Entry(it) } ?: BuilderFocus.Narrow
        PanelMove.Left -> BuilderFocus.Tab(SourceKindTab.Pick)
        else -> BuilderFocus.Search
    }

private fun fromEntry(
    view: NavView,
    focus: BuilderFocus.Entry,
    move: PanelMove,
): BuilderFocus {
    val at = view.entryKeys.indexOf(focus.key)
    return when {
        move == PanelMove.Left -> BuilderFocus.Tab(view.tab)
        move == PanelMove.Right -> focus
        move == PanelMove.Up && at > 0 -> BuilderFocus.Entry(view.entryKeys[at - 1])
        move == PanelMove.Up -> if (view.tab == SourceKindTab.Pick) BuilderFocus.Search else focus
        at in 0 until view.entryKeys.lastIndex -> BuilderFocus.Entry(view.entryKeys[at + 1])
        else -> BuilderFocus.Narrow
    }
}

private fun fromNarrow(
    view: NavView,
    move: PanelMove,
): BuilderFocus =
    when (move) {
        PanelMove.Up -> view.entryKeys.lastOrNull()?.let { BuilderFocus.Entry(it) } ?: BuilderFocus.Tab(view.tab)
        PanelMove.Down -> if (view.narrowOpen) BuilderFocus.NarrowRow(NarrowRowKind.Genres) else BuilderFocus.Next
        PanelMove.Left -> BuilderFocus.Tab(view.tab)
        PanelMove.Right -> BuilderFocus.Next
    }

private fun fromNarrowRow(
    focus: BuilderFocus.NarrowRow,
    move: PanelMove,
): BuilderFocus {
    val rows = NarrowRowKind.entries
    return when {
        !move.vertical -> focus
        move == PanelMove.Up && focus.row.ordinal == 0 -> BuilderFocus.Narrow
        move == PanelMove.Up -> BuilderFocus.NarrowRow(rows[focus.row.ordinal - 1])
        focus.row.ordinal == rows.lastIndex -> BuilderFocus.Next
        else -> BuilderFocus.NarrowRow(rows[focus.row.ordinal + 1])
    }
}

private fun fromNext(
    view: NavView,
    move: PanelMove,
): BuilderFocus =
    when {
        move == PanelMove.Up && view.step == BuilderStep.Play -> BuilderFocus.PlayRow(view.playRows.last())
        view.step == BuilderStep.Play -> BuilderFocus.Next
        move == PanelMove.Up && view.narrowOpen -> BuilderFocus.NarrowRow(NarrowRowKind.entries.last())
        move == PanelMove.Up || move == PanelMove.Left -> BuilderFocus.Narrow
        else -> BuilderFocus.Next
    }

// Down off the last row of a page lands on Next; the Play page is the one with a Next below its rows.
private fun onNext(
    focus: BuilderFocus.PlayRow,
    move: PanelMove,
): BuilderFocus = if (move == PanelMove.Down) BuilderFocus.Next else focus

// The row above or below [row] in [rows] for a vertical key; null for a key that is not vertical or runs off the list.
private fun <T> inList(
    rows: List<T>,
    row: T,
    move: PanelMove,
    focusOf: (T) -> BuilderFocus,
): BuilderFocus? {
    val at = rows.indexOf(row)
    val to = at + move.step
    return when {
        !move.vertical -> focusOf(row)
        at < 0 -> rows.firstOrNull()?.let(focusOf)
        to in rows.indices -> focusOf(rows[to])
        else -> null
    }
}
