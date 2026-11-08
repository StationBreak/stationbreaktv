// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.domain.selection.LibrarySetup
import tv.stationbreak.core.domain.selection.SetupResult
import tv.stationbreak.core.model.ItemId

/**
 * A [LibrarySetup] for tests (ADR-0004) over a [SelectionStore]: [choose] is a setup's save ([storeChoice]), refused
 * while a choice is stored, as the real one refuses it, and [clear] is [SelectionWriter.clearLibraries]. No repair
 * record or library cache is behind them (the real one is `LibrarySelection`). It records every call, so a screen's
 * test can prove its path went through the setup.
 */
class InMemoryLibrarySetup(
    private val store: SelectionStore,
) : LibrarySetup {
    /** Every choice passed to [choose], in order, whether or not it was stored. */
    val chosen = mutableListOf<Set<ItemId>>()

    /** How many times [clear] was called. */
    var clears = 0
        private set

    override suspend fun choose(ids: Set<ItemId>): SetupResult {
        chosen += ids
        if (store.loadSelection() != null) return SetupResult.Failed
        return if (store.storeChoice(ids)) SetupResult.Saved else SetupResult.Failed
    }

    override suspend fun clear(): Boolean {
        clears++
        return store.clearLibraries()
    }
}
