// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId

/** How many spellings [idSpellings] gives at most, so a query that binds them knows how many ids fit a chunk. */
internal const val MAX_SPELLINGS = 5

/**
 * The spellings an item or series may be stored under for [raw]: the one it names, the canonical one and the dashed
 * one, each in lower and upper case. SQL cannot compare canonical forms and a row is stored as the server spelled it;
 * the caller matches the rows it gets back by canonical ID (spec 10, Vimes L).
 */
internal fun idSpellings(raw: String): List<String> {
    val canonical = CanonicalId.parseOrNull(raw)?.value ?: return listOf(raw)
    val dashed =
        listOf(
            canonical.substring(0, 8),
            canonical.substring(8, 12),
            canonical.substring(12, 16),
            canonical.substring(16, 20),
            canonical.substring(20),
        ).joinToString("-")
    return listOf(raw, canonical, canonical.uppercase(), dashed, dashed.uppercase())
}
