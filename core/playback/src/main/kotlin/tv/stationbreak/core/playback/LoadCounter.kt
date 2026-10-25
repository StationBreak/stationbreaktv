// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import kotlin.math.abs

/**
 * What the media requests of one play cost before its tracks were known (diagnostics only, #392): how many data
 * source opens (HTTP requests) there were, the time of their opens in all and the longest, the bytes read, where the
 * opens started, and the container type. Numbers and the container's MIME subtype only; never a URI, a path, a header
 * or a name. [ioMs] is the wall time inside the main media's opens and reads, so the tracks time minus it is parse
 * time. [opensKb] is the main media's open start positions in KB, in order, comma-separated, at most
 * [MAX_OPENS_LISTED] of them and a trailing `+` when there were more.
 */
data class LoadStats(
    val loads: Int,
    val loadMs: Long,
    val maxLoadMs: Long,
    val loadKb: Long,
    val container: String,
    val opensKb: String,
    val ioMs: Long = 0,
)

/** How many open positions [LoadStats.opensKb] lists. */
internal const val MAX_OPENS_LISTED = 8

/** Where a [CountingDataSource] reports. Calls come from loader threads. */
internal interface RequestSink {
    /**
     * An open of play [generation] at byte [position] ended (returned or threw) after [durationMs] whole ms;
     * [openNanos] is the same time unrounded.
     */
    fun opened(
        generation: Long,
        position: Long,
        durationMs: Long,
        openNanos: Long = durationMs * NANOS_PER_MS,
    )

    /** [count] more bytes were read for play [generation]; the read took [readNanos], even if it moved no bytes. */
    fun read(
        generation: Long,
        count: Long,
        readNanos: Long = 0,
    )

    /** The counting failed: drop this play's numbers. */
    fun fail()
}

/**
 * Counts the data source opens of the play now on the player, from [reset] (the play call) until [close] (its tracks
 * are known). Unlike the load events, which see a progressive file as one long load, this sees every reopen the
 * extractor makes when it seeks. Called from the player's looper ([reset], [close]) and from loader threads (the
 * rest), so every call is synchronized. Each data source passes back the generation of the media item it was made
 * for, so the late opens and reads of the previous play are ignored.
 *
 * Limits: an open still in flight when the tracks are known is not counted (it ends too late); opens and reads that
 * land just before the tracks event reaches the looper are counted; the time of an open is connect plus response
 * headers (not the first body byte); failed and interrupted opens count.
 */
internal class LoadCounter : RequestSink {
    private val lock = Any()
    private var current = 0L
    private var loads = 0
    private var loadMs = 0L
    private var maxLoadMs = 0L
    private var bytes = 0L
    private var ioNanos = 0L
    private val positions = ArrayList<Long>()
    private var closed: LoadStats? = null
    private var open = true
    private var broken = false

    /** Starts the count of play [generation]. */
    fun reset(generation: Long) =
        synchronized(lock) {
            current = generation
            broken = false
            loads = 0
            loadMs = 0
            maxLoadMs = 0
            bytes = 0
            ioNanos = 0
            positions.clear()
            closed = null
            open = true
        }

    override fun opened(
        generation: Long,
        position: Long,
        durationMs: Long,
        openNanos: Long,
    ) = synchronized(lock) {
        if (!open || generation != current) return@synchronized
        loads++
        loadMs += durationMs
        ioNanos += openNanos
        maxLoadMs = maxOf(maxLoadMs, durationMs)
        // One past the cap is kept so a capped list can be told from one that is exactly full.
        if (positions.size <= MAX_OPENS_LISTED) positions += abs(position) / BYTES_PER_KB
    }

    override fun read(
        generation: Long,
        count: Long,
        readNanos: Long,
    ) = synchronized(lock) {
        if (open && generation == current) {
            bytes += count
            ioNanos += readNanos
        }
    }

    override fun fail() = synchronized(lock) { broken = true }

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
                opensList(),
                ioNanos / NANOS_PER_MS,
            )
        }

    private fun opensList(): String {
        val listed = positions.take(MAX_OPENS_LISTED).joinToString(",")
        return if (positions.size > MAX_OPENS_LISTED) "$listed+" else listed
    }

    private companion object {
        const val BYTES_PER_KB = 1024L
    }
}

private const val NANOS_PER_MS = 1_000_000L

/** A monotonic clock; an interface so the timing can be tested, and so a read costs no boxing. */
internal fun interface NanoClock {
    fun now(): Long
}

private const val NO_GENERATION = -1L

/** The generation a media item carries: its media id (the player sets it to the play's generation), else none. */
internal fun generationOf(mediaId: String): Long = mediaId.toLongOrNull() ?: NO_GENERATION

/** A factory whose data sources report to [sink] as play [generation], leaving out opens of [isSidecar] URIs. */
@UnstableApi
internal fun DataSource.Factory.counting(
    sink: RequestSink,
    generation: Long,
    isSidecar: (Uri?) -> Boolean = { false },
): DataSource.Factory = DataSource.Factory { CountingDataSource(createDataSource(), sink, generation, isSidecar) }

/**
 * Builds each media item's source from a data source factory that counts as the play named by the item's media id,
 * and leaves out the opens of the item's own subtitle files. Tying the generation to the item, not to when a data
 * source happens to be made, keeps the previous play's late sources out of the new play's count.
 */
@UnstableApi
internal class CountingMediaSourceFactory(
    private val dataSource: DataSource.Factory,
    private val sink: RequestSink,
) : MediaSource.Factory {
    private var drm: DrmSessionManagerProvider? = null
    private var policy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drm = provider
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        policy = loadErrorHandlingPolicy
        return this
    }

    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(dataSource).supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val sidecars =
            mediaItem.localConfiguration
                ?.subtitleConfigurations
                ?.map { it.uri }
                .orEmpty()
                .toSet()
        val counted = dataSource.counting(sink, generationOf(mediaItem.mediaId)) { it != null && it in sidecars }
        val factory = DefaultMediaSourceFactory(counted)
        drm?.let { factory.setDrmSessionManagerProvider(it) }
        policy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(mediaItem)
    }
}

/**
 * Passes everything to [delegate] and tells [sink] how long each open took and how much was read. Opens of a sidecar
 * URI ([isSidecar], compared here and never kept) and the reads after them are not reported. The counting is
 * diagnostics: a fault in it drops the play's numbers and never reaches the data source's caller. The delegate's own
 * exceptions pass through untouched.
 */
@UnstableApi
internal class CountingDataSource(
    private val delegate: DataSource,
    private val sink: RequestSink,
    private val generation: Long,
    private val isSidecar: (Uri?) -> Boolean = { false },
    private val clock: NanoClock = NanoClock { System.nanoTime() },
) : DataSource {
    private var counted = true

    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val sidecar = guarded(false) { isSidecar(dataSpec.uri) }
        return timedOpen(dataSpec.position, sidecar) { delegate.open(dataSpec) }
    }

    /** Runs [open], an open at [position], and reports its time unless [sidecar]; its exception passes through. */
    @Throws(IOException::class)
    fun timedOpen(
        position: Long,
        sidecar: Boolean,
        open: () -> Long,
    ): Long {
        counted = !sidecar
        val start = clock.now()
        try {
            return open()
        } finally {
            val nanos = clock.now() - start
            if (counted) guarded(Unit) { sink.opened(generation, position, nanos / NANOS_PER_MS, nanos) }
        }
    }

    @Throws(IOException::class)
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val start = clock.now()
        var count = 0
        try {
            count = delegate.read(buffer, offset, length)
            return count
        } finally {
            val nanos = clock.now() - start
            if (counted) guarded(Unit) { sink.read(generation, maxOf(count, 0).toLong(), nanos) }
        }
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    @Throws(IOException::class)
    override fun close() = delegate.close()

    private inline fun <T> guarded(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            dropNumbers()
            fallback
        }

    private fun dropNumbers() {
        try {
            sink.fail()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Nothing more can be done; the play goes on.
        }
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
