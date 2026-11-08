// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.domain.selection.LibrarySetup
import tv.stationbreak.core.domain.selection.SetupResult
import tv.stationbreak.core.model.ItemId

/**
 * A [LibrarySetup] for tests (ADR-0004) over [settings]: [choose] is a setup's save ([ServerSettings.saveLibraries])
 * and [clear] is [ServerSettings.clearLibraries], with no repair record or library cache behind them (the real one is
 * `LibrarySelection`). It records every call, so a screen's test can prove its path went through the setup.
 */
class InMemoryLibrarySetup(
    private val settings: ServerSettings,
) : LibrarySetup {
    /** Every choice passed to [choose], in order, whether or not it was stored. */
    val chosen = mutableListOf<Set<ItemId>>()

    /** How many times [clear] was called. */
    var clears = 0
        private set

    override suspend fun choose(ids: Set<ItemId>): SetupResult {
        chosen += ids
        return if (settings.saveLibraries(ids)) SetupResult.Saved else SetupResult.Failed
    }

    override suspend fun clear(): Boolean {
        clears++
        return settings.clearLibraries()
    }
}
