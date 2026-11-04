// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ChannelId

/**
 * The play keys of the last few lineup revisions and days, so a list is not hashed again for each emission of the same
 * revision on the same day. A key holds what is in force on the day being played (the curation basis, a starter's
 * rules for the day), so it is kept per broadcast day too (by the day's next boundary): keys worked out before a
 * boundary are not reused after it, even before the roll writes a new revision. Thread-safe.
 */
internal class PlayKeyCache {
    // Most recently used last; the oldest goes when there are more than [REVISIONS].
    private val kept =
        object : LinkedHashMap<Pair<Long, Long>, Keys>(REVISIONS, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Keys>) = size > REVISIONS
        }

    /** The keys of [revision] on the day whose next boundary is [boundaryMs], worked out by [compute] once. */
    fun keysOf(
        revision: Long,
        boundaryMs: Long,
        compute: () -> Keys,
    ): Keys = synchronized(kept) { kept.getOrPut(revision to boundaryMs, compute) }

    private companion object {
        const val REVISIONS = 8
        const val LOAD = 0.75f
    }
}

private typealias Keys = Map<ChannelId, PlayKey>
