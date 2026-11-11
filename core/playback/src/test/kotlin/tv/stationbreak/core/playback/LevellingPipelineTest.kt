// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The processor driven the way the player's audio sink drives it (#498 follow-up): through a real Media3
 * [AudioProcessingPipeline] with the sink's own processors around it, configure -> flush -> queueInput... ->
 * queueEndOfStream -> getOutput until ended, then a new format, a reset. Levelling off must behave as an identity
 * processor in every step: every byte out unchanged, and the end of a stream reached.
 */
@UnstableApi
class LevellingPipelineTest {
    private val rate = 48_000

    private class Case(
        val channels: Int,
        val encoding: Int,
        val leading: Boolean,
    ) {
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = channels * bytesPerSample

        override fun toString() = "channels=$channels encoding=$encoding leading=$leading"
    }

    private val cases =
        listOf(2, 6).flatMap { ch ->
            listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT).flatMap { enc ->
                listOf(true, false).map { lead -> Case(ch, enc, lead) }
            }
        }

    /**
     * The sink's order: its trimming step (active when the audio has an encoder delay or padding, stood in for here by
     * [PassThrough] when [trimming]), format conversion, the app's processor, then the sink's own chain (silence skip,
     * speed).
     */
    private fun pipeline(
        levelling: LevellingAudioProcessor,
        trimming: Boolean = false,
    ) = AudioProcessingPipeline(
        ImmutableList
            .builder<AudioProcessor>()
            .apply { if (trimming) add(PassThrough()) }
            .add(ToInt16PcmAudioProcessor(), levelling, SilenceSkippingAudioProcessor(), SonicAudioProcessor())
            .build(),
    )

    /** An active processor that changes nothing, as the sink's trimming one is with nothing left to trim. */
    private class PassThrough : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioFormat) = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
        }
    }

    /** [seconds] of a quiet sine in [case]'s layout, as raw bytes in native order. */
    private fun pcm(
        case: Case,
        seconds: Double,
    ): ByteArray {
        val frames = (seconds * rate).toInt()
        val buf = ByteBuffer.allocate(frames * case.frameSize).order(ByteOrder.nativeOrder())
        repeat(frames) { f ->
            val v = 0.1 * sin(2 * PI * 997.0 * f / rate)
            repeat(case.channels) {
                if (case.encoding == C.ENCODING_PCM_FLOAT) {
                    buf.putFloat(v.toFloat())
                } else {
                    buf.putShort((v * Short.MAX_VALUE).toInt().toShort())
                }
            }
        }
        return buf.array()
    }

    /**
     * Feeds [input] in [chunk]-byte buffers as the sink does (a buffer stays queued until it is consumed), then ends
     * the stream and drains it. Returns everything that came out; fails if the pipeline stops making progress.
     */
    private fun drive(
        p: AudioProcessingPipeline,
        input: ByteArray,
        chunk: Int,
        endStream: Boolean = true,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun take() {
            val o = p.getOutput()
            while (o.hasRemaining()) out.write(o.get().toInt())
        }
        var at = 0
        var idle = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            val buf = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            buf.put(input, at, n).flip()
            while (buf.hasRemaining()) {
                val before = buf.remaining()
                p.queueInput(buf)
                take()
                idle = if (buf.remaining() == before) idle + 1 else 0
                assertTrue("input stuck with ${buf.remaining()} bytes left", idle < STUCK)
            }
            at += n
        }
        if (!endStream) return out.toByteArray()
        p.queueEndOfStream()
        var rounds = 0
        while (!p.isEnded) {
            take()
            val waiting = p.getOutput().remaining()
            assertTrue("the pipeline never ended its stream: $waiting bytes waiting", ++rounds < STUCK)
        }
        take()
        return out.toByteArray()
    }

    private fun configured(
        case: Case,
        trimming: Boolean = false,
        enabled: () -> Boolean,
    ): Pair<AudioProcessingPipeline, LevellingAudioProcessor> {
        val levelling = LevellingAudioProcessor(LoudnessLevel(), { case.leading }, enabled)
        val p = pipeline(levelling, trimming)
        p.configure(AudioFormat(rate, case.channels, case.encoding))
        p.flush()
        return p to levelling
    }

    @Test
    fun offFromTheStartPassesEveryByteAndEndsTheStream() {
        for (case in cases) {
            val (p, _) = configured(case) { false }
            val input = pcm(case, 0.5)
            val out = drive(p, input, chunk = case.frameSize * 1024)
            // 16-bit in, 16-bit out; float is converted to 16-bit by the sink's processor before levelling.
            val expected = if (case.encoding == C.ENCODING_PCM_FLOAT) toInt16(input) else input
            assertArrayEquals("$case", expected, out)
        }
    }

    @Test
    fun onEndsTheStreamToo() {
        for (case in cases) {
            val (p, _) = configured(case) { true }
            val input = pcm(case, 0.5)
            val out = drive(p, input, chunk = case.frameSize * 1024)
            val outFrameSize = case.channels * 2
            val inFrames = input.size / case.frameSize
            assertTrue("$case: ${out.size / outFrameSize} frames out of $inFrames", out.size / outFrameSize >= inFrames)
        }
    }

    @Test
    fun theStreamEndsBehindAnActiveProcessor() {
        // The pipeline asks a processor to end its stream again on every pass once the one before it has ended; the
        // look-ahead's tail must leave once, or the stream never ends and the sink never drains.
        for (case in cases) {
            for (enabled in listOf(true, false)) {
                val (p, _) = configured(case, trimming = true) { enabled }
                val input = pcm(case, 0.5)
                val out = drive(p, input, chunk = case.frameSize * 1024)
                val inFrames = input.size / case.frameSize
                val outFrames = out.size / (case.channels * 2)
                val tail = if (enabled) LOOKAHEAD_FRAMES - 1 else 0
                assertEquals("$case enabled=$enabled", inFrames + tail, outFrames)
            }
        }
    }

    @Test
    fun aStreamWithNoInputEndsBehindAnActiveProcessor() {
        for (case in cases) {
            for (enabled in listOf(true, false)) {
                val (p, _) = configured(case, trimming = true) { enabled }
                drive(p, ByteArray(0), chunk = 1)
            }
        }
    }

    @Test
    fun offEndsAStreamThatHadNoInput() {
        for (case in cases) {
            val (p, _) = configured(case) { false }
            drive(p, ByteArray(0), chunk = 1)
        }
    }

    @Test
    fun offCarriesOnAcrossAFormatChangeAndAReset() {
        for (case in cases) {
            val (p, levelling) = configured(case) { false }
            drive(p, pcm(case, 0.2), chunk = case.frameSize * 512)
            // The next item: another layout, as the sink does it: configure, flush, play.
            val next = Case(if (case.channels == 2) 6 else 2, case.encoding, case.leading)
            p.configure(AudioFormat(44_100, next.channels, next.encoding))
            p.flush()
            val input = pcm(next, 0.2)
            val out = drive(p, input, chunk = next.frameSize * 512)
            assertEquals("$case", if (next.encoding == C.ENCODING_PCM_FLOAT) input.size / 2 else input.size, out.size)
            // A renderer reset, then the next stream.
            p.reset()
            p.configure(AudioFormat(rate, case.channels, case.encoding))
            p.flush()
            drive(p, pcm(case, 0.2), chunk = case.frameSize * 512)
            assertTrue(levelling.isActive)
        }
    }

    @Test
    fun offPassesBuffersThatAreNotWholeFrames() {
        for (case in cases.filter { it.encoding == C.ENCODING_PCM_16BIT }) {
            val (p, _) = configured(case) { false }
            val input = pcm(case, 0.2)
            // Odd sizes: the sink hands over whatever the decoder gave.
            val out = drive(p, input, chunk = case.frameSize * 100 + 2)
            assertArrayEquals("$case", input, out)
        }
    }

    @Test
    fun flippingTheSettingMidStreamStillEndsTheStream() {
        for (case in cases) {
            var on = true
            val (p, _) = configured(case) { on }
            drive(p, pcm(case, 0.3), chunk = case.frameSize * 512, endStream = false)
            on = false
            drive(p, pcm(case, 0.3), chunk = case.frameSize * 512, endStream = false)
            on = true
            drive(p, pcm(case, 0.3), chunk = case.frameSize * 512)
            p.flush()
            on = false
            drive(p, pcm(case, 0.3), chunk = case.frameSize * 512)
        }
    }

    @Test
    fun offTakesAnEmptyInputBeforeAnyAudio() {
        // The sink asks the pipeline for output before it queues a buffer: the pipeline then hands each processor the
        // shared empty buffer, which a fresh (or reset) processor still holds as its own output buffer.
        for (case in cases) {
            val (p, levelling) = configured(case) { false }
            p.getOutput()
            levelling.queueInput(AudioProcessor.EMPTY_BUFFER)
        }
    }

    @Test
    fun offTakesAnEmptyInputAfterFlushesAndAReset() {
        // The device's order: configure, audio, flush, flush, then the next pass with nothing queued; and a reset.
        for (case in cases) {
            val (p, _) = configured(case) { false }
            drive(p, pcm(case, 0.1), chunk = case.frameSize * 512, endStream = false)
            p.flush()
            p.flush()
            p.getOutput()
            drive(p, pcm(case, 0.1), chunk = case.frameSize * 512)
            p.reset()
            p.configure(AudioFormat(rate, case.channels, case.encoding))
            p.flush()
            p.getOutput()
            val input = pcm(case, 0.1)
            val out = drive(p, input, chunk = case.frameSize * 512)
            assertEquals("$case", if (case.encoding == C.ENCODING_PCM_FLOAT) input.size / 2 else input.size, out.size)
        }
    }

    @Test
    fun aFlushBeforeAnyFormatDoesNotThrow() {
        // The pipeline flushes every processor it holds, active or not, and a reset leaves them without a format.
        for (enabled in listOf(false, true)) {
            val p = pipeline(LevellingAudioProcessor(enabled = { enabled }))
            p.flush()
            p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            p.flush()
            p.reset()
            p.flush()
        }
    }

    private fun toInt16(float: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(float).order(ByteOrder.nativeOrder())
        val dst = ByteBuffer.allocate(float.size / 2).order(ByteOrder.nativeOrder())
        while (src.hasRemaining()) {
            val v = src.getFloat().coerceIn(-1f, 1f)
            dst.putShort((v * Short.MAX_VALUE).toInt().toShort())
        }
        return dst.array()
    }

    private companion object {
        const val STUCK = 10_000

        /** 5 ms at 48 kHz. */
        const val LOOKAHEAD_FRAMES = 240
    }
}
