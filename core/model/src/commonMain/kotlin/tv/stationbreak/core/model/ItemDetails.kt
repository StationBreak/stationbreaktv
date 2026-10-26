// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

/**
 * What the info overlay and the guide's details panel show about one item, fetched on demand: not part of the saved
 * library. The text is the server's, already made safe for display by the adapter (tags, control and direction
 * characters removed, length capped), so a screen may draw it as it is. A missing field is null, or empty for [genres].
 */
data class ItemDetails(
    val id: ItemId,
    val overview: String?,
    val year: Int?,
    val genres: List<String>,
    val runtime: Duration?,
)
