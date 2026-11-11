// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * What volume levelling has learned, shared by every player slot (the front and the warm standby).
 *
 * [gainDb] is the gain of the audible player, in dB: the processor behind it writes it; a processor built later starts
 * from it. Each item's own gain is remembered by its item key (the server's media id, never a title or a URL) in a
 * small in-memory LRU of [REMEMBERED_ITEMS] entries, for the process only, so a return to an item starts at the gain it
 * had (#498). Written from audio threads, read from others.
 */
class LoudnessLevel {
    @Volatile
    var gainDb: Double = 0.0
        internal set

    private val items =
        object : LinkedHashMap<String, Double>(REMEMBERED_ITEMS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > REMEMBERED_ITEMS
        }

    /** The gain [key]'s item last had, or null if it is not remembered. */
    fun recall(key: String): Double? = synchronized(items) { items[key] }

    /** Remembers [gainDb] as [key]'s item's gain. */
    fun remember(
        key: String,
        gainDb: Double,
    ) {
        synchronized(items) { items[key] = gainDb }
    }

    companion object {
        /** How many items' gains are remembered: the least recently used goes first. */
        const val REMEMBERED_ITEMS = 256
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Volume levelling for a player: whether it is [enabled] now (asked as audio is processed, so a change in Settings
 * takes effect at once) and the [level] its slots share. A factory owns one, so the learned gains outlive a player.
 */
class VolumeLevelling(
    val enabled: () -> Boolean,
    val level: LoudnessLevel = LoudnessLevel(),
)
