// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library

/** The line a change's result shows. */
internal fun ChangeResult.notice(): Notice =
    when (this) {
        ChangeResult.Saved -> Notice.Saved
        ChangeResult.LastLibrary -> Notice.LastLibrary
        ChangeResult.NotListed -> Notice.NotListed
        ChangeResult.Failed -> Notice.Failed
    }

/**
 * The rows of the Libraries screen in [phase]: the [listed] libraries in the server's order, then the stored ones it
 * left out (shown ticked until the selection confirms them missing: a design-partner placeholder). Read-only, the
 * stored ones alone, unless [listOf] is false (nobody signed in). [names] are the names earlier listings gave.
 * A row is ticked when it is stored (chosen or being added), and adding while its add is out and not given up.
 */
internal fun libraryRows(
    phase: LibrariesPhase,
    listed: List<Library>,
    view: ChoiceView?,
    names: Map<ItemId, String>,
    listOf: Boolean,
): List<LibraryRow> {
    val stored = view?.let { it.chosen + it.adding }.orEmpty()

    fun row(
        id: ItemId,
        name: String?,
    ) = LibraryRow(
        id,
        name,
        ticked = id in stored,
        adding = view != null && id in view.adding && id !in view.stalled,
    )

    val byName = compareBy<ItemId>({ names[it] == null }, { names[it]?.lowercase() }, { it.value })
    return when (phase) {
        LibrariesPhase.Loading -> {
            emptyList()
        }

        LibrariesPhase.Listed -> {
            val listedIds = listed.map { it.id }.toSet()
            val leftOut = (stored - listedIds).sortedWith(byName)
            listed.map { row(it.id, it.name) } + leftOut.map { row(it, names[it]) }
        }

        is LibrariesPhase.Unavailable -> {
            if (listOf) stored.sortedWith(byName).map { row(it, names[it]) } else emptyList()
        }
    }
}
