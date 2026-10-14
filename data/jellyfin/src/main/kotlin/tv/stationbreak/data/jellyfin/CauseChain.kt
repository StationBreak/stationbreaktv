// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import java.util.Collections
import java.util.IdentityHashMap

internal const val MAX_CAUSE_CHAIN = 10

/**
 * This throwable, its causes and the failures suppressed beside them, outermost first, breadth first, stopping at a
 * cycle or after [MAX_CAUSE_CHAIN] entries. Suppressed failures matter: OkHttp tries a name's addresses in parallel,
 * throws the first failure and attaches the others, so a TLS failure can sit behind an unreachable address.
 */
internal fun Throwable.causeChain(): List<Throwable> = causeGraph().nodes

/** The read throwables, and whether the cap left some unread: an unread failure could be a TLS one. */
internal class CauseGraph(
    val nodes: List<Throwable>,
    val truncated: Boolean,
)

internal fun Throwable.causeGraph(): CauseGraph {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val found = ArrayList<Throwable>()
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty() && found.size < MAX_CAUSE_CHAIN) {
        val next = queue.removeFirst()
        if (!seen.add(next)) continue
        found += next
        next.cause?.let(queue::add)
        queue.addAll(next.suppressed)
    }
    return CauseGraph(found, truncated = queue.any { it !in seen })
}

/** True when the message holds any of [fragments]. The message is only looked at, never kept. */
internal fun Throwable.says(vararg fragments: String): Boolean {
    val text = message ?: return false
    return fragments.any { text.contains(it, ignoreCase = true) }
}
