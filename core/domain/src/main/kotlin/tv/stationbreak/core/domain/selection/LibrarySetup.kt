// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import tv.stationbreak.core.model.ItemId

/** What a setup's save came to. */
enum class SetupResult { Saved, Failed }

/**
 * The library choice of onboarding (spec 26, Part 4): every sign-in path clears it ([clear]), and the library picker's
 * Done stores it ([choose]). Both go through the selection's own lock, so they never cross a change, a landing add or
 * the resume at start.
 */
interface LibrarySetup {
    /**
     * Stores [ids] as the chosen libraries, as a setup: the TV's repair record is emptied first, nothing is being
     * added, and the first sync is asked to read what it has not read yet. [SetupResult.Failed] when nothing was
     * stored (an empty choice included).
     */
    suspend fun choose(ids: Set<ItemId>): SetupResult

    /**
     * Drops the chosen libraries, keeping the seq, before a new sign-in's choice. True when they were dropped or there
     * was nothing to drop.
     */
    suspend fun clear(): Boolean
}
