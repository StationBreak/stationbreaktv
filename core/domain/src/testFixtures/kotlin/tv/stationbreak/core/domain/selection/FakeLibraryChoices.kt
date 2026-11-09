// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [LibraryChoices] for feature tests: the stored choice is [selection], [listing] answers [answer] (the server's
 * [libraries] by default), and [change] records each call, waits on [gate] when one is set, and answers [result]. A
 * [ChangeResult.Saved] change is stored as the real selection stores it: chosen libraries kept or unticked, adds kept
 * or dropped, and a new tick added as being added.
 */
class FakeLibraryChoices(
    initial: ChoiceView?,
    var libraries: List<Library> = emptyList(),
) : LibraryChoices {
    override val selection = MutableStateFlow(initial)

    /** What [listing] answers; the server's [libraries] with the stored choice by default. */
    var answer: () -> LibraryListing = { LibraryListing.Listed(libraries, selection.value) }

    /** What [change] answers. */
    @Volatile var result: ChangeResult = ChangeResult.Saved

    /** When set, [change] waits on it before it answers. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** The ticked sets [change] was called with, in order. */
    val changes = CopyOnWriteArrayList<Set<ItemId>>()

    @Volatile var listings = 0
        private set

    override suspend fun listing(): LibraryListing {
        listings++
        return answer()
    }

    override suspend fun change(ticked: Set<ItemId>): ChangeResult {
        changes += ticked
        gate?.await()
        val answered = result
        val stored = selection.value
        if (answered == ChangeResult.Saved && stored != null) {
            val chosen = stored.chosen intersect ticked
            val adding = (stored.adding intersect ticked) + (ticked - stored.chosen - stored.adding)
            selection.value = stored.copy(chosen = chosen, adding = adding, stalled = stored.stalled intersect adding)
        }
        return answered
    }

    companion object {
        /** The server could not be asked. */
        val timeout: MediaServerError = MediaServerError.Timeout()
    }
}
