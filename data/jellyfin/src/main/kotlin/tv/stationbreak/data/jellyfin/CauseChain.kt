// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import java.util.Collections
import java.util.IdentityHashMap

internal const val MAX_CAUSE_CHAIN = 10

/** This throwable and its causes, outermost first, stopping at a cycle or after [MAX_CAUSE_CHAIN] entries. */
internal fun Throwable.causeChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }
        .takeWhile { seen.add(it) }
        .take(MAX_CAUSE_CHAIN)
        .toList()
}

/** True when the message holds any of [fragments]. The message is only looked at, never kept. */
internal fun Throwable.says(vararg fragments: String): Boolean {
    val text = message ?: return false
    return fragments.any { text.contains(it, ignoreCase = true) }
}
