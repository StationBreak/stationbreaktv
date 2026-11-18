// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Parsed paths for the path strings of a frame, least recently used out first. Many strings repeat from frame to frame
 * (the cap, the speaker lines, the mouths) and are parsed once; the ones that change every frame (an ear, an arm) are
 * misses, so a miss reuses the [Path] of the entry it pushes out instead of allocating a new one.
 *
 * Not thread-safe: it is used from the draw phase only.
 */
internal class StanPathCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val parser = PathParser()
    private val entries = LinkedHashMap<String, Path>(capacity * 2, LOAD_FACTOR, true)
    private var spare: Path? = null

    /** The path for [d]. Do not keep it: it may be handed out again for another string. */
    fun get(d: String): Path {
        entries[d]?.let { return it }
        val path = spare?.also { spare = null } ?: Path()
        path.rewind()
        parser.clear()
        parser.parsePathString(d).toPath(path)
        entries[d] = path
        if (entries.size > capacity) {
            val eldest = entries.entries.iterator()
            val out = eldest.next()
            eldest.remove()
            spare = out.value
        }
        return path
    }

    /** How many strings are held now. */
    val size: Int get() = entries.size

    private companion object {
        const val DEFAULT_CAPACITY = 96
        const val LOAD_FACTOR = 0.75f
    }
}
