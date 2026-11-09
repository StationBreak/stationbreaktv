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
 * selection is [gone] (the server lists none of the chosen libraries any more). [stalled] are the adds the add pass
 * gave up for this run of the app (a subset of [adding]): they stay stored, and the next start asks for them again.
 * No stamp, seq or miss. Its text holds counts only.
 */
data class ChoiceView(
    val chosen: Set<ItemId>,
    val adding: Set<ItemId>,
    val gone: Boolean,
    val stalled: Set<ItemId> = emptySet(),
) {
    override fun toString(): String {
        val counts = "chosen=${chosen.size}, adding=${adding.size}, stalled=${stalled.size}"
        return "ChoiceView($counts, gone=$gone)"
    }
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

    /** The server could not be asked: [error] says why. */
    data class Unavailable(
        val error: MediaServerError,
        val selection: ChoiceView?,
    ) : LibraryListing

    /** Nobody is signed in, so the server was not asked. */
    data class NotSignedIn(
        val selection: ChoiceView?,
    ) : LibraryListing
}

/** How features show and change the chosen libraries after setup (spec 26, Part 4): no stamps, seqs or misses. */
interface LibraryChoices {
    /** The stored selection, or null when none is stored. */
    val selection: StateFlow<ChoiceView?>

    /**
     * Asks the server for its libraries once, and answers as soon as it has, never waiting on a change. What it lists
     * is followed afterwards, off the caller's path (one listing for the two-read rule).
     */
    suspend fun listing(): LibraryListing

    /**
     * Returns once every listing asked for so far has been followed: for a caller that acts on what a listing changed
     * (Sync now). Nothing to wait for by default.
     */
    suspend fun followed() = Unit

    /**
     * Makes [ticked] the chosen libraries: a library unticked leaves at once (held while a programme of it is on air),
     * and one ticked is added (it is read in the background, and joins the channels once its read has ended).
     */
    suspend fun change(ticked: Set<ItemId>): ChangeResult
}
