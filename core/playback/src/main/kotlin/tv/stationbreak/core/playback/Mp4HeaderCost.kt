// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.StreamKind

/**
 * What a direct-play MP4's header costs the Java heap before the first frame (#735). Media3's `Mp4Extractor` reads the
 * whole `moov` box and builds every track's sample table (offset, size, time and flags for each sample) in one step
 * that cannot be bounded or interrupted, for every track in the file, including those the player will never select.
 * A long film with several audio tracks needs well over 100 MiB for it: on the Insignia (192 MiB heap) the parse ran
 * out of memory three times in 60 flips, on one direct-play MP4, while the heap at a flip was 26 to 85 MiB.
 *
 * The estimate is samples times [BYTES_PER_SAMPLE]. Samples are the runtime times each embedded video and audio track's
 * samples per second: a video track's frame rate, an audio track's sample rate over its codec's samples per frame.
 * Subtitle tracks are left out (a few samples a minute). A fact the server did not send takes a default
 * ([DEFAULT_FRAME_RATE], [DEFAULT_SAMPLE_RATE], [AAC_FRAME] for a codec not listed). Matroska and the other
 * containers are not affected: their extractors keep no per-sample table.
 *
 * Known edges. With no runtime the estimate is 0 and the file direct-plays (fails open: the server sends the runtime
 * for every item the app schedules, and the error line's `runtimeMin=-1` names such a case). A fragmented MP4 keeps
 * its samples in fragments, not in the `moov`, so its real header is small; the estimate does not know that and may
 * send a long fragmented file to the direct stream, which costs the server a remux and nothing else.
 */
object Mp4HeaderCost {
    /**
     * The heap one sample's table entries take while the header is parsed. Measured with Media3 1.11.1's
     * `Mp4Extractor` on the JVM (the smallest -Xmx that parses, less the 5 MiB a 10 s file needs): 51 to 58 bytes
     * per sample over files of 0.5 M to 1.5 M samples. Rounded up for the device's object overhead.
     */
    const val BYTES_PER_SAMPLE = 64L

    /**
     * The share of the heap limit an MP4 header may take on direct play: one part in this many. On a 192 MiB heap that
     * is 64 MiB, which still direct-plays an ordinary film (2 h 20 min, one video and two AAC tracks: 60 MiB estimated,
     * about 48 MiB real) and refuses the measured four-track file (83 MiB real). The rest of the heap is the app (26 to
     * 85 MiB at a flip on the Insignia, garbage included) and a superseded play's parse, which Media3 cannot interrupt
     * and so may still run beside the new one. A warm standby's source comes from the same resolver, so it is guarded
     * too (and the Insignia does not warm).
     */
    const val HEAP_SHARE_DIVISOR = 3L

    /** A budget that refuses nothing: the resolver's default, so only the app opts in. */
    const val NO_GUARD = Long.MAX_VALUE

    /** The container names (ffmpeg's, as the server sends them, possibly comma-separated) read by `Mp4Extractor`. */
    private val MP4_FAMILY = setOf(Codecs.MP4, Codecs.M4V, Codecs.MOV, "m4a", "3gp", "3g2", "mj2")

    private const val DEFAULT_FRAME_RATE = 30.0
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val AAC_FRAME = 1_024

    /** Audio samples (PCM frames) per stored sample, by codec; [AAC_FRAME] for one not listed. */
    private val SAMPLES_PER_FRAME =
        mapOf(
            Codecs.AAC to AAC_FRAME,
            Codecs.VORBIS to AAC_FRAME,
            Codecs.MP3 to 1_152,
            Codecs.AC3 to 1_536,
            Codecs.EAC3 to 1_536,
            Codecs.DTS to 512,
            Codecs.OPUS to 960,
            Codecs.FLAC to 4_096,
            "alac" to 4_096,
            // One TrueHD access unit is 1/1200 s: 40 samples at 48 kHz.
            Codecs.TRUEHD to 40,
        )

    /** The byte budget for a heap limit of [maxHeapBytes] (`Runtime.maxMemory()`). */
    fun budgetFor(maxHeapBytes: Long): Long = maxHeapBytes / HEAP_SHARE_DIVISOR

    /** True when [media] is read by Media3's MP4 extractor on direct play. */
    internal fun isMp4(media: MediaSource): Boolean =
        media.container
            .orEmpty()
            .split(',')
            .any { Codecs.normalize(it) in MP4_FAMILY }

    /** The estimated sample count of [media]'s embedded video and audio tracks; 0 with no runtime. */
    internal fun samples(media: MediaSource): Long {
        val seconds = media.runtime?.inWholeMilliseconds?.div(MS_PER_SECOND.toDouble()) ?: return 0L
        val perSecond =
            media.streams
                .filter { !it.isExternal }
                .sumOf { stream ->
                    when (stream.kind) {
                        StreamKind.VIDEO -> {
                            stream.frameRate ?: DEFAULT_FRAME_RATE
                        }

                        StreamKind.AUDIO -> {
                            val frame = SAMPLES_PER_FRAME[Codecs.normalize(stream.codec)] ?: AAC_FRAME
                            (stream.sampleRate ?: DEFAULT_SAMPLE_RATE).toDouble() / frame
                        }

                        StreamKind.SUBTITLE -> {
                            0.0
                        }
                    }
                }
        return (seconds * perSecond).toLong()
    }

    /** The estimated heap the header of [media] takes on direct play; 0 when it is not an MP4. */
    internal fun estimatedBytes(media: MediaSource): Long = if (isMp4(media)) samples(media) * BYTES_PER_SAMPLE else 0L

    /** True when direct play's header parse of [media] fits [budgetBytes]. */
    internal fun fits(
        media: MediaSource,
        budgetBytes: Long,
    ): Boolean = estimatedBytes(media) <= budgetBytes

    private const val MS_PER_SECOND = 1_000L
}
