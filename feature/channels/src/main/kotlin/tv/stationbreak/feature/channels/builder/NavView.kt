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
        is BuilderFocus.NarrowRow -> fromNarrowRow(view, focus, move)
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
        // Down off the last tab goes to Next, so the page can be left without crossing to the other column.
        move == PanelMove.Down && focus.tab.ordinal == SourceKindTab.entries.lastIndex -> {
            BuilderFocus.Next
        }

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
        PanelMove.Left -> BuilderFocus.Tab(SourceKindTab.Pick)
        PanelMove.Right -> BuilderFocus.Search
        else -> fromSourceList(view, BuilderFocus.Search, move)
    }

private fun fromEntry(
    view: NavView,
    focus: BuilderFocus.Entry,
    move: PanelMove,
): BuilderFocus =
    when (move) {
        PanelMove.Left -> BuilderFocus.Tab(view.tab)
        PanelMove.Right -> focus
        else -> fromSourceList(view, focus, move)
    }

// The Sources list's vertical keys: one line up or down in [sourceFocusOrder]. Up off the top (the Narrow line) goes
// to the tab row, as Up off the top of any list does; Down off the bottom goes to Next, the footer under the list.
private fun fromSourceList(
    view: NavView,
    focus: BuilderFocus,
    move: PanelMove,
): BuilderFocus {
    val order = sourceFocusOrder(view)
    val at = order.indexOf(focus)
    return when {
        at < 0 -> order.first()
        move == PanelMove.Up -> order.getOrNull(at - 1) ?: BuilderFocus.Tab(view.tab)
        move == PanelMove.Down -> order.getOrNull(at + 1) ?: BuilderFocus.Next
        else -> focus
    }
}

private fun fromNarrow(
    view: NavView,
    move: PanelMove,
): BuilderFocus =
    when (move) {
        PanelMove.Left -> BuilderFocus.Tab(view.tab)
        PanelMove.Right -> BuilderFocus.Next
        else -> fromSourceList(view, BuilderFocus.Narrow, move)
    }

private fun fromNarrowRow(
    view: NavView,
    focus: BuilderFocus.NarrowRow,
    move: PanelMove,
): BuilderFocus = if (move.vertical) fromSourceList(view, focus, move) else focus

private fun fromNext(
    view: NavView,
    move: PanelMove,
): BuilderFocus =
    when {
        view.step == BuilderStep.Play && move == PanelMove.Up -> BuilderFocus.PlayRow(view.playRows.last())
        view.step == BuilderStep.Play -> BuilderFocus.Next
        move == PanelMove.Up -> sourceFocusOrder(view).last()
        move == PanelMove.Left -> BuilderFocus.Narrow
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

/**
 * The lines of the Sources list the D-pad stops on, top to bottom. The rule is filters first, then the metadata
 * sources: Narrow down comes first, with its rows under it when open; the Pick tab's search line is next; the tab's
 * entries come last. A new filter kind goes in the filter block, so it lands at the top too. The list draws in this
 * order.
 */
internal fun sourceFocusOrder(view: NavView): List<BuilderFocus> =
    buildList {
        add(BuilderFocus.Narrow)
        if (view.narrowOpen) NarrowRowKind.entries.forEach { add(BuilderFocus.NarrowRow(it)) }
        if (view.tab == SourceKindTab.Pick) add(BuilderFocus.Search)
        view.entryKeys.forEach { add(BuilderFocus.Entry(it)) }
    }
