// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind

/** The library choice a user gets by accepting the defaults on the picker (S0.5.4, decision 7). */
object LibraryDefaults {
    /** Every library in [libraries] except home videos. */
    fun ticked(libraries: List<Library>): Set<ItemId> {
        val usable = libraries.filter { it.kind != LibraryKind.HomeVideos }
        return usable.map { it.id }.toSet()
    }
}
