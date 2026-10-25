// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.abs

/**
 * What the media requests of one play cost before its tracks were known (diagnostics only, #392): how many data
 * source opens (HTTP requests) there were, the time of their opens to the first byte in all and the longest, the
 * bytes read, the largest jump between successive opens' start positions (an index read at the far end of the file),
 * and the container type. Numbers and the container's MIME subtype only; never a URI, a path, a header or a name.
 */
data class LoadStats(
    val loads: Int,
    val loadMs: Long,
    val maxLoadMs: Long,
    val loadKb: Long,
    val container: String,
    val seekKb: Long = 0,
)

/**
 * Counts the data source opens of the play now on the player, from [reset] (the play call) until [close] (its tracks
 * are known). Unlike the load events, which see a progressive file as one long load, this sees every reopen the
 * extractor makes when it seeks. Called from the player's looper ([reset], [close]) and from loader threads (the
 * rest), so every call is synchronized. Each data source remembers the [generation] it was made under and passes it
 * back, so the late opens and reads of the previous play are ignored. [fail] drops the play's numbers (a fault in
 * the counting).
 */
internal open class LoadCounter {
    private val lock = Any()
    private var current = 0L
    private var loads = 0
    private var loadMs = 0L
    private var maxLoadMs = 0L
    private var bytes = 0L
    private var seekBytes = 0L
    private var lastPosition = -1L
    private var closed: LoadStats? = null
    private var open = true
    private var broken = false

    /** The generation of the play now counted; a data source made now carries it. */
    val generation: Long get() = synchronized(lock) { current }

    /** Starts the count of play [generation]. */
    fun reset(generation: Long) =
        synchronized(lock) {
            current = generation
            broken = false
            loads = 0
            loadMs = 0
            maxLoadMs = 0
            bytes = 0
            seekBytes = 0
            lastPosition = -1
            closed = null
            open = true
        }

    /** An open of play [generation] starting at [position] returned its first byte after [durationMs]. */
    fun opened(
        generation: Long,
        position: Long,
        durationMs: Long,
    ) = synchronized(lock) {
        if (!open || generation != current) return@synchronized
        loads++
        loadMs += durationMs
        maxLoadMs = maxOf(maxLoadMs, durationMs)
        if (lastPosition >= 0) seekBytes = maxOf(seekBytes, abs(position - lastPosition))
        lastPosition = position
    }

    /** [count] more bytes were read for play [generation]. */
    open fun read(
        generation: Long,
        count: Long,
    ) = synchronized(lock) {
        if (open && generation == current) bytes += count
    }

    /** Drops this play's numbers: the counting failed, and a wrong count is worse than none. */
    fun fail() = synchronized(lock) { broken = true }

    /** Ends the count at the tracks and returns it; null when no open was seen. Later calls return the same. */
    fun close(containerMime: String?): LoadStats? =
        synchronized(lock) {
            if (open) {
                open = false
                closed = stats(containerMime)
            }
            closed
        }

    private fun stats(containerMime: String?): LoadStats? =
        if (loads == 0 || broken) {
            null
        } else {
            LoadStats(
                loads,
                loadMs,
                maxLoadMs,
                bytes / BYTES_PER_KB,
                containerSubtype(containerMime),
                seekBytes / BYTES_PER_KB,
            )
        }

    private companion object {
        const val BYTES_PER_KB = 1024L
    }
}

/** A factory whose data sources report their opens and reads to [counter], under the generation current at creation. */
@UnstableApi
internal fun DataSource.Factory.counting(counter: LoadCounter): DataSource.Factory =
    DataSource.Factory { CountingDataSource(createDataSource(), counter, counter.generation) }

/**
 * Passes everything to [delegate] and tells [counter] how long each open took to its first byte and how much was
 * read. The counting is diagnostics: a fault in it drops the play's numbers and never reaches the data source's
 * caller. The delegate's own exceptions pass through untouched.
 */
@UnstableApi
internal class CountingDataSource(
    private val delegate: DataSource,
    private val counter: LoadCounter,
    private val generation: Long,
) : DataSource {
    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long = timedOpen(dataSpec.position) { delegate.open(dataSpec) }

    /** Runs [open], an open at [position], and reports its time to the first byte; its exception passes through. */
    @Throws(IOException::class)
    fun timedOpen(
        position: Long,
        open: () -> Long,
    ): Long {
        val start = System.nanoTime()
        try {
            return open()
        } finally {
            val ms = (System.nanoTime() - start) / NANOS_PER_MS
            safely { counter.opened(generation, position, ms) }
        }
    }

    @Throws(IOException::class)
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = delegate.read(buffer, offset, length)
        if (count > 0) safely { counter.read(generation, count.toLong()) }
        return count
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    @Throws(IOException::class)
    override fun close() = delegate.close()

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            counter.fail()
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
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
