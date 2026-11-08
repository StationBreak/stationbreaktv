// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library

/** What a change of the chosen libraries came to (spec 26, Part 4). */
enum class ChangeResult {
    /** Stored (or there was nothing to change). */
    Saved,

    /** Nothing stored: the change would leave no chosen library. Libraries still being added do not count. */
    LastLibrary,

    /** Nothing stored: a library to add was not in the last listing of the server's libraries. */
    NotListed,

    /** Nothing stored: the change could not be saved. */
    Failed,
}

/**
 * The stored selection as features see it: the [chosen] libraries, the ones being [adding], and whether the
 * selection is [gone] (the server lists none of the chosen libraries any more). No stamp, seq or miss. Its text
 * holds counts only.
 */
data class ChoiceView(
    val chosen: Set<ItemId>,
    val adding: Set<ItemId>,
    val gone: Boolean,
) {
    override fun toString(): String = "ChoiceView(chosen=${chosen.size}, adding=${adding.size}, gone=$gone)"
}

/** One read of the server's libraries, with the stored selection after it was followed. */
sealed interface LibraryListing {
    /** The server listed [libraries]. */
    data class Listed(
        val libraries: List<Library>,
        val selection: ChoiceView?,
    ) : LibraryListing {
        override fun toString(): String = "Listed(libraries=${libraries.size}, selection=$selection)"
    }

    /** The server could not be asked: [error] says why, or is null when nobody is signed in. */
    data class Unavailable(
        val error: MediaServerError?,
        val selection: ChoiceView?,
    ) : LibraryListing
}

/** How features show and change the chosen libraries after setup (spec 26, Part 4): no stamps, seqs or misses. */
interface LibraryChoices {
    /** The stored selection, or null when none is stored. */
    val selection: StateFlow<ChoiceView?>

    /** Asks the server for its libraries once, and follows what it lists (one listing for the two-read rule). */
    suspend fun listing(): LibraryListing

    /**
     * Makes [ticked] the chosen libraries: a library unticked leaves at once (held while a programme of it is on air),
     * and one ticked is added (it is read in the background, and joins the channels once its read has ended).
     */
    suspend fun change(ticked: Set<ItemId>): ChangeResult
}

/**
 * The background read of the libraries being added (spec 26, Part 4). [restart] only asks for it: it replaces any read
 * under way with one that reads the libraries being added as they are stored now, and never waits for it.
 */
fun interface LibraryPasses {
    fun restart()

    companion object {
        /** Nothing reads the added libraries: for a build without the add pass. */
        val None: LibraryPasses = LibraryPasses {}
    }
}
