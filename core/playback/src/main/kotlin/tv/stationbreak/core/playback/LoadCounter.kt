// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * What the media loads of one play cost before its tracks were known (diagnostics only, #392): how many started, how
 * long they took in all and the longest, how much they read, and the container type. Numbers and the container's MIME
 * subtype only; never a URI, a path, a header or a name.
 */
data class LoadStats(
    val loads: Int,
    val loadMs: Long,
    val maxLoadMs: Long,
    val loadKb: Long,
    val container: String,
)

/**
 * Counts the media loads of the play now on the player, from [reset] (the play call) until [close] (its tracks are
 * known). Used on the player's looper only. A load that has not finished when the tracks are known counts as started
 * but adds no time or bytes. Only loads started for this play are counted, by task id: the cancels of the previous
 * play's loads, which arrive after [reset], are not. [fail] drops the play's numbers (a listener fault).
 */
internal class LoadCounter {
    private var loads = 0
    private var loadMs = 0L
    private var maxLoadMs = 0L
    private var bytes = 0L
    private var closed: LoadStats? = null
    private var open = true
    private var broken = false
    private val tasks = HashSet<Long>()

    fun reset() {
        tasks.clear()
        broken = false
        loads = 0
        loadMs = 0
        maxLoadMs = 0
        bytes = 0
        closed = null
        open = true
    }

    /** Load [taskId] started; [forThisPlay] is false for a load of some other media item, which is ignored. */
    fun started(
        taskId: Long,
        forThisPlay: Boolean = true,
    ) {
        if (!open || !forThisPlay) return
        tasks += taskId
        loads++
    }

    /** Drops this play's numbers: the listener failed, and a wrong count is worse than none. */
    fun fail() {
        broken = true
    }

    /** Load [taskId] ended, completed or not: [durationMs] and [bytes] are what it took and read. */
    fun finished(
        taskId: Long,
        durationMs: Long,
        bytes: Long,
    ) {
        if (!open || taskId !in tasks) return
        loadMs += durationMs
        maxLoadMs = maxOf(maxLoadMs, durationMs)
        this.bytes += bytes
    }

    /** Ends the count at the tracks and returns it; null when no load was seen. Later calls return the same. */
    fun close(containerMime: String?): LoadStats? {
        if (open) {
            open = false
            closed = stats(containerMime)
        }
        return closed
    }

    private fun stats(containerMime: String?): LoadStats? =
        if (loads == 0 || broken) {
            null
        } else {
            LoadStats(loads, loadMs, maxLoadMs, bytes / BYTES_PER_KB, containerSubtype(containerMime))
        }

    private companion object {
        const val BYTES_PER_KB = 1024L
    }
}

private val TOKEN = Regex("[a-z0-9.+-]+")

/** The subtype of a container MIME type (`video/x-matroska` is `x-matroska`), or `unknown` if not a plain token. */
internal fun containerSubtype(mime: String?): String =
    mime
        ?.substringAfter('/', "")
        ?.lowercase()
        ?.takeIf { TOKEN.matches(it) }
        ?: "unknown"
