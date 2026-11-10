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
 * stored ones alone, unless [signedIn] is false (nobody signed in). [names] are the names earlier listings gave.
 * A row is ticked as [wanted] says when a press asked for it, else when it is stored (chosen or being added). It is
 * pending while a press is not stored yet ([wanted] differs from the stored choice, or [sending] carries it), and
 * adding while its add is stored, out and not given up.
 */
@Suppress("LongParameterList") // The visit's phase and listing, the stored choice, the presses, and the names.
internal fun libraryRows(
    phase: LibrariesPhase,
    listed: List<Library>,
    view: ChoiceView?,
    names: Map<ItemId, String>,
    signedIn: Boolean,
    wanted: Map<ItemId, Boolean> = emptyMap(),
    sending: Set<ItemId> = emptySet(),
): List<LibraryRow> {
    val stored = view?.let { it.chosen + it.adding }.orEmpty()
    val shown = stored.wantedAs(wanted)

    fun row(
        id: ItemId,
        name: String?,
    ): LibraryRow {
        val want = wanted[id]
        val pending = want != null && (want != id in stored || id in sending)
        return LibraryRow(
            id,
            name,
            ticked = want ?: (id in stored),
            adding = !pending && view != null && id in view.adding && id !in view.stalled,
            pending = pending,
        )
    }

    val byName = compareBy<ItemId>({ names[it] == null }, { names[it]?.lowercase() }, { it.value })
    return when (phase) {
        LibrariesPhase.Loading -> {
            emptyList()
        }

        LibrariesPhase.Listed -> {
            val listedIds = listed.map { it.id }.toSet()
            // A left-out library unticked stays, as saving, until its untick is stored.
            val leftOut = (stored + shown - listedIds).sortedWith(byName)
            listed.map { row(it.id, it.name) } + leftOut.map { row(it, names[it]) }
        }

        is LibrariesPhase.Unavailable -> {
            if (signedIn) (stored + shown).sortedWith(byName).map { row(it, names[it]) } else emptyList()
        }
    }
}

/** These ticks with [wanted] applied: each library pressed is ticked or not as it asks. */
internal fun Set<ItemId>.wantedAs(wanted: Map<ItemId, Boolean>): Set<ItemId> =
    this - wanted.filterValues { !it }.keys + wanted.filterValues { it }.keys
