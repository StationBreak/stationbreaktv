// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The selection's tunables (spec 26, Part 4). [missingConfirmAfter]: how long a chosen or adding library must have been
 * missing from the server's listings, across two counted listings, before it is taken out of the selection. Listings
 * miss libraries for a while (a server restarting, a view briefly hidden), so one miss never removes anything.
 */
data class SelectionConfig(
    val missingConfirmAfter: Duration = 3.hours,
)
