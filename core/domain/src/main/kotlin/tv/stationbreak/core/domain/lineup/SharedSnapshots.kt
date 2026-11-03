// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope

/**
 * The library snapshots that the schedule source builds the lineup's days from and the channel preview lays a draft
 * out from, kept once (see `SnapshotCache`): a preview of a draft reads no cut-off again that the lineup has read. The
 * app makes one and gives it to both. Reads run in [scope], not in the caller.
 */
class SharedSnapshots(
    scope: CoroutineScope,
) {
    internal val cache = SnapshotCache(scope)
}
