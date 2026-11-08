// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId

/**
 * Where an item that left the library came back under a new id (spec 26): for each of [of]'s `gone` ids that it
 * matches, the new id, found only among the [of]'s `libraries`. A match is a candidate only; the repair still checks
 * it against the channel's rules for the day and its holds, and fills the block instead when it fails. The app's is
 * `LibraryRepository.successors`, which is conservative: a shared identity id (`imdb`, `tmdb` or `tvdb` only) with no
 * identity key that disagrees, else the same series, season and episode, else the same film title and year; a
 * disagreeing identity id blocks those name fallbacks. [None] matches nothing.
 */
fun interface Successors {
    suspend fun of(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        gone: Set<CanonicalId>,
    ): Map<CanonicalId, CanonicalId>

    companion object {
        /** Matches nothing: every gone block is filled. */
        val None: Successors = Successors { _, _, _ -> emptyMap() }
    }
}

/**
 * A pinned programme whose library a change of the selection removes: [library] airs until [endMs] (the repair's
 * `pinLibrary` and `pinEndMs`), so its rows are held that long, and the pause hold after it.
 */
data class Pin(
    val library: ItemId,
    val endMs: Long,
)
