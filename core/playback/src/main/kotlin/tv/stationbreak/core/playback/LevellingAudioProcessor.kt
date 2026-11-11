// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Slow automatic gain on decoded PCM, then a look-ahead peak limiter (volume levelling, #498).
 *
 * It measures short-term loudness (ITU-R BS.1770 K-weighting, a 3 s window of 100 ms blocks), steers a gain toward
 * [TARGET_LKFS] no faster than [MAX_SLEW_DB_PER_SEC] and within [-MAX_CUT_DB, +MAX_BOOST_DB], and follows it with a
 * limiter whose ceiling is [CEILING_DBFS]. The limiter looks ahead [LOOKAHEAD_SEC]: the audio is delayed by that, and
 * the gain reduction a peak needs is reached by a smooth ramp that ends as the peak arrives, so a hard jump is turned
 * down before it comes instead of being clipped. The ramp is a moving average of a sliding minimum, which keeps the
 * result at or under the ceiling for every sample; the release is [LIMITER_RELEASE_SEC].
 *
 * Each item is levelled from its own gain (#498): [itemKey] is the item the player gave this slot, taken at a flush. A
 * new key starts from the gain the [LoudnessLevel] remembers for it, or from 0 dB, never from the last channel's; and
 * for its first [SETTLE_SEC] of audible audio (also after a re-enable) it measures over the [MOMENTARY_WINDOW_SEC]
 * window and moves up to [FAST_SLEW_DB_PER_SEC], so a flip settles in about a second; then the 3 s window and
 * [MAX_SLEW_DB_PER_SEC] again. With no key (null) the gain carries over between items, as before.
 *
 * The processor of the audible player ([leading]) shares its gain through [LoudnessLevel.gainDb] and remembers it
 * for its item (every [REMEMBER_EVERY_SEC], at a new item and at the end of a stream). A standby levels its own item
 * for itself and writes nothing, so at a hand-over it carries on from the gain it learned for that item. [enabled] is
 * asked for each buffer: off, audio passes unchanged (unity gain) and nothing is measured.
 *
 * Only 16-bit and float PCM are handled; any other encoding leaves the processor inactive. Passthrough audio (AC3,
 * E-AC3, DTS, TrueHD) never reaches an audio processor: the sink skips the chain for encoded audio.
 *
 * Silence: a 100 ms block quieter than [SILENCE_GATE_LKFS] is left out of the average, and with no block left the
 * gain is held, so pauses and gaps do not wind it up to the boost cap. (Gating the whole 3 s window instead lets a
 * show's tail drain out of it and raises the gain by about 3 dB.)
 *
 * `flush` within an item (a seek) and `reset` keep the gain, clear the measurement and the limiter; the limiter
 * catches a loud start while the gain settles.
 */
@UnstableApi
@Suppress("TooManyFunctions")
class LevellingAudioProcessor(
    private val level: LoudnessLevel = LoudnessLevel(),
    private val leading: () -> Boolean = { true },
    private val enabled: () -> Boolean = { true },
    private val itemKey: () -> String? = { null },
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channels = 0
    private var float = false

    private var weights = DoubleArray(0)
    private var shelf = Biquad()
    private var highPass = Biquad()
    private var z = DoubleArray(0) // per channel: shelf z1, z2, highpass z1, z2

    private var blockFrames = 1
    private var blockFill = 0
    private var blockEnergy = 0.0
    private val ring = DoubleArray(WINDOW_BLOCKS) { GATED }
    private var ringCount = 0
    private var ringPos = 0
    private var ringSum = 0.0

    private var gainDb = level.gainDb
    private var gainLin = dbToLin(gainDb)
    private var gainStep = 0.0
    private var limiter = 1.0
    private var releaseCoef = 0.0

    // The item this processor is levelling (the key the player gave its slot, taken at a flush), the audible blocks
    // left in its settle phase, and whether its gain has moved, as the front's, since it was last remembered.
    private var key: String? = null
    private var settleLeft = 0
    private var unsaved = false
    private var sinceRemembered = 0

    // The look-ahead: [delay] holds the last [lookahead] gained frames; the deque (two ring arrays) is the sliding
    // minimum of the reduction each frame needs; [smooth] holds its last [lookahead] values for the moving average.
    private var lookahead = 2
    private var delay = DoubleArray(0)
    private var delayPos = 0
    private var minIdx = LongArray(0)
    private var minVal = DoubleArray(0)
    private var minHead = 0
    private var minSize = 0
    private var frameNo = 0L
    private var smooth = DoubleArray(0)
    private var smoothPos = 0
    private var smoothSum = 0.0
    private var wasEnabled = true

    // Levelled audio of this stream is still in the delay line: the end of the stream lets it out, once.
    private var held = false
    private var frame = DoubleArray(0)
    private var lastPath = PATH_NONE

    /** The largest output magnitude before the final guard clamp (for tests: it must stay at the ceiling). */
    internal var maxPreClamp = 0.0
        private set

    /** The gain now, in dB (for tests). */
    internal val currentGainDb: Double get() = gainDb

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val pcm =
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val ok = pcm && inputAudioFormat.channelCount > 0 && inputAudioFormat.sampleRate > 0
        if (PlaybackLog.on) {
            PlaybackLog.line(
                "levelling configure encoding=${inputAudioFormat.encoding} channels=${inputAudioFormat.channelCount} " +
                    "rate=${inputAudioFormat.sampleRate} active=$ok enabled=${enabled()} leading=${leading()} " +
                    "wasEnabled=$wasEnabled",
            )
        }
        return if (ok) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush() {
        if (PlaybackLog.on) {
            PlaybackLog.line(
                "levelling flush enabled=${enabled()} leading=${leading()} wasEnabled=$wasEnabled gainDb=$gainDb",
            )
        }
        lastPath = PATH_NONE
        held = false
        // The pipeline flushes every processor it holds, one with no format too (after a reset): nothing to set up.
        if (!isActive) {
            sampleRate = 0
            channels = 0
            return
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        weights = channelWeights(channels)
        shelf = Biquad.highShelf(sampleRate)
        highPass = Biquad.highPass(sampleRate)
        z = DoubleArray(channels * STATE_PER_CHANNEL)
        blockFrames = max(1, sampleRate / BLOCKS_PER_SEC)
        blockFill = 0
        blockEnergy = 0.0
        ring.fill(GATED)
        ringCount = 0
        ringPos = 0
        ringSum = 0.0
        // Within an item the gain is kept (a seek); a new item starts from its own (see the class comment).
        adoptItem()
        gainStep = 0.0
        gainLin = dbToLin(gainDb)
        limiter = 1.0
        releaseCoef = 1.0 - exp(-1.0 / (LIMITER_RELEASE_SEC * sampleRate))
        resetLookahead()
        frame = DoubleArray(channels)
    }

    /**
     * A new item key since the last flush: the last item's gain is remembered, and the new one starts from the gain
     * it had (or from 0 dB), in the settle phase. Taken only at a flush, where the old item's audio has left the
     * pipeline, so its tail is never measured as the new item's.
     */
    private fun adoptItem() {
        val next = itemKey()
        if (next == key) return
        if (unsaved) rememberGain()
        key = next
        val remembered = next?.let(level::recall)
        gainDb = remembered ?: 0.0
        settleLeft = SETTLE_BLOCKS
        sinceRemembered = 0
        if (leading()) level.gainDb = gainDb
        if (PlaybackLog.on) {
            PlaybackLog.line("levelling item remembered=${remembered != null} leading=${leading()} gainDb=$gainDb")
        }
    }

    private fun rememberGain() {
        key?.let { level.remember(it, gainDb) }
        unsaved = false
        sinceRemembered = 0
    }

    private fun resetLookahead() {
        lookahead = max(2, (LOOKAHEAD_SEC * sampleRate).roundToInt())
        delay = DoubleArray(lookahead * channels)
        delayPos = 0
        minIdx = LongArray(lookahead)
        minVal = DoubleArray(lookahead)
        minHead = 0
        minSize = 0
        frameNo = 0L
        smooth = DoubleArray(lookahead) { 1.0 }
        smoothPos = 0
        smoothSum = lookahead.toDouble()
    }

    override fun onReset() {
        // The gain stays, as at a flush: a reset between two items must not drop the viewer back to 0 dB.
        gainStep = 0.0
        limiter = 1.0
        sampleRate = 0
        channels = 0
        held = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Nothing to take, nothing to give: Media3's pipeline hands every processor the shared EMPTY_BUFFER when no
        // audio is queued, and a fresh or reset processor still holds that same buffer as its output, so copying it
        // would be ByteBuffer.put(itself), which throws IllegalArgumentException (#498: levelling off failed tunes).
        if (!inputBuffer.hasRemaining()) return
        if (!enabled()) {
            logPath(PATH_BYPASS)
            bypass(inputBuffer)
            return
        }
        logPath(PATH_LEVELLING)
        if (!wasEnabled) {
            // Back on after a stretch off: nothing stale in the delay line, the limiter or the window, and the gain
            // starts from 0 dB, not from what an earlier show learned, in the settle phase.
            gainDb = 0.0
            if (leading()) level.gainDb = 0.0
            onFlush()
            settleLeft = SETTLE_BLOCKS
            wasEnabled = true
        }
        val bytes = if (float) Float.SIZE_BYTES else Short.SIZE_BYTES
        val frames = inputBuffer.remaining() / (channels * bytes)
        val out = replaceOutputBuffer(frames * channels * bytes)
        repeat(frames) {
            for (c in 0 until channels) {
                frame[c] = if (float) inputBuffer.getFloat().toDouble() else inputBuffer.getShort() / PCM16_SCALE
            }
            step(out)
        }
        out.flip()
        if (frames > 0) held = true
    }

    // One line per change of path (and the first after a flush), never per buffer; nothing is built while off.
    private fun logPath(path: Int) {
        if (path == lastPath || !PlaybackLog.on) return
        lastPath = path
        PlaybackLog.line(
            "levelling path=${if (path == PATH_BYPASS) "bypass" else "levelling"} enabled=${enabled()} " +
                "leading=${leading()} wasEnabled=$wasEnabled gainDb=$gainDb",
        )
    }

    // Off: unity gain, the audio passes as it came.
    private fun bypass(inputBuffer: ByteBuffer) {
        wasEnabled = false
        held = false
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    override fun onQueueEndOfStream() {
        // Media3's pipeline asks again on every pass once the processor before this one has ended: the tail leaves
        // once, or the stream never ends. Off, or with nothing levelled since the flush, there is no tail.
        // The item has ended: its gain is remembered.
        if (unsaved) rememberGain()
        if (!held) return
        held = false
        // The frames still in the delay line leave through the same path, behind [lookahead] - 1 frames of silence.
        val bytes = if (float) Float.SIZE_BYTES else Short.SIZE_BYTES
        val out = replaceOutputBuffer((lookahead - 1) * channels * bytes)
        frame.fill(0.0)
        repeat(lookahead - 1) { step(out) }
        out.flip()
    }

    /** The frame in [frame] through the measurement and the look-ahead; one frame, [lookahead] - 1 older, out. */
    private fun step(out: ByteBuffer) {
        var peak = 0.0
        for (c in 0 until channels) {
            val s = frame[c]
            val y = highPass.step(z, c * STATE_PER_CHANNEL + 2, shelf.step(z, c * STATE_PER_CHANNEL, s))
            blockEnergy += weights[c] * y * y
            peak = max(peak, abs(s))
        }
        gainLin += gainStep
        // The reduction this frame needs once gained; the sliding minimum of it, smoothed, is what the limiter wants.
        val gained = peak * gainLin
        val need = if (gained > CEILING_LIN) CEILING_LIN / gained else 1.0
        val wanted = smoothed(slidingMin(need))
        limiter = min(wanted, limiter + (1.0 - limiter) * releaseCoef)
        // The oldest frame is read before the new one overwrites its place.
        val readAt = ((delayPos + 1) % lookahead) * channels
        val writeAt = delayPos * channels
        for (c in 0 until channels) {
            val raw = delay[readAt + c] * limiter
            // The limiter alone must hold the ceiling; the clamp below is a last guard, and a test asserts it is idle.
            maxPreClamp = max(maxPreClamp, abs(raw))
            val v = raw.coerceIn(-CEILING_LIN, CEILING_LIN)
            if (float) {
                out.putFloat(v.toFloat())
            } else {
                val pcm = (v * PCM16_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(pcm.toShort())
            }
        }
        for (c in 0 until channels) delay[writeAt + c] = frame[c] * gainLin
        delayPos = (delayPos + 1) % lookahead
        if (++blockFill == blockFrames) endBlock()
    }

    // The smallest of the last [lookahead] reductions, by a monotonic deque.
    private fun slidingMin(need: Double): Double {
        // Entries older than the window go first, so the push below can never land on a live one.
        while (minSize > 0 && minIdx[minHead] <= frameNo - lookahead) {
            minHead = (minHead + 1) % lookahead
            minSize--
        }
        while (minSize > 0 && minVal[(minHead + minSize - 1) % lookahead] >= need) minSize--
        val tail = (minHead + minSize) % lookahead
        minIdx[tail] = frameNo
        minVal[tail] = need
        minSize++
        frameNo++
        return minVal[minHead]
    }

    // The moving average of the last [lookahead] minima: a ramp, not a step.
    private fun smoothed(minimum: Double): Double {
        smoothSum += minimum - smooth[smoothPos]
        smooth[smoothPos] = minimum
        smoothPos = (smoothPos + 1) % lookahead
        return min(1.0, smoothSum / lookahead)
    }

    private fun endBlock() {
        val energy = blockEnergy / blockFrames
        blockEnergy = 0.0
        blockFill = 0
        // The ring holds the energy of the last 30 blocks; a block under the silence gate is kept out of the average
        // (marked GATED), so a show's tail draining out of the window cannot pull the loudness down and the gain up.
        val old = ring[ringPos]
        if (old != GATED) {
            ringSum -= old
            ringCount--
        }
        val audible = lkfs(energy) > SILENCE_GATE_LKFS
        if (audible) {
            ring[ringPos] = energy
            ringSum += energy
            ringCount++
        } else {
            ring[ringPos] = GATED
        }
        ringPos = (ringPos + 1) % WINDOW_BLOCKS
        // Settling: the momentary window and the fast slew. After it: the short-term window and the slow slew.
        val settling = settleLeft > 0
        val loudness = if (settling) momentaryLoudness() else shortTermLoudness()
        if (loudness != null) {
            val target = (TARGET_LKFS - loudness).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
            val maxMove = (if (settling) FAST_SLEW_DB_PER_SEC else MAX_SLEW_DB_PER_SEC) / BLOCKS_PER_SEC
            gainDb += (target - gainDb).coerceIn(-maxMove, maxMove)
            if (audible && settling) settleLeft--
            // Only the audible player's gain is shared and remembered; a standby learns its own item for itself.
            if (leading()) {
                level.gainDb = gainDb
                unsaved = true
                if (++sinceRemembered >= REMEMBER_EVERY_BLOCKS) rememberGain()
            }
        }
        // Ramp the linear gain across the next block so a step is never heard.
        gainStep = (dbToLin(gainDb) - gainLin) / blockFrames
    }

    /** The loudness of the ungated blocks of the short-term window, or null with none. */
    private fun shortTermLoudness(): Double? = if (ringCount > 0) lkfs(max(ringSum, 0.0) / ringCount) else null

    /** The loudness of the ungated blocks of the momentary window (the last [MOMENTARY_BLOCKS]), or null with none. */
    private fun momentaryLoudness(): Double? {
        var sum = 0.0
        var count = 0
        for (i in 1..MOMENTARY_BLOCKS) {
            val e = ring[(ringPos - i + WINDOW_BLOCKS) % WINDOW_BLOCKS]
            if (e != GATED) {
                sum += e
                count++
            }
        }
        return if (count > 0) lkfs(sum / count) else null
    }

    /** One biquad section in transposed direct form II; state lives in the caller's array. */
    private class Biquad(
        val b0: Double = 1.0,
        val b1: Double = 0.0,
        val b2: Double = 0.0,
        val a1: Double = 0.0,
        val a2: Double = 0.0,
    ) {
        fun step(
            z: DoubleArray,
            at: Int,
            x: Double,
        ): Double {
            val y = b0 * x + z[at]
            z[at] = b1 * x - a1 * y + z[at + 1]
            z[at + 1] = b2 * x - a2 * y
            return y
        }

        companion object {
            // BS.1770 stage 1 (head-related shelf) and stage 2 (RLB high-pass), re-derived for any sample rate.
            fun highShelf(fs: Int): Biquad {
                val f0 = 1681.974450955533
                val gainDb = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(Math.PI * f0 / fs)
                val vh = 10.0.pow(gainDb / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1.0 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2.0 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2.0 * (k * k - 1.0) / a0,
                    (1.0 - k / q + k * k) / a0,
                )
            }

            fun highPass(fs: Int): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(Math.PI * f0 / fs)
                val a0 = 1.0 + k / q + k * k
                return Biquad(1.0, -HP_B1, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
            }
        }
    }

    companion object {
        /** ATSC A/85 target. */
        const val TARGET_LKFS = -24.0
        const val MAX_BOOST_DB = 12.0
        const val MAX_CUT_DB = 12.0
        const val MAX_SLEW_DB_PER_SEC = 1.0

        /** The settle phase of a new item (or of a re-enable): this long of audible audio, ... */
        const val SETTLE_SEC = 6.0

        /** ... measured over the EBU momentary window ... */
        const val MOMENTARY_WINDOW_SEC = 0.4

        /** ... and steered this fast. */
        const val FAST_SLEW_DB_PER_SEC = 8.0

        /** How often the audible item's gain is remembered while it plays. */
        const val REMEMBER_EVERY_SEC = 2.0
        const val CEILING_DBFS = -1.0
        const val LIMITER_RELEASE_SEC = 0.1

        /** How far ahead the limiter looks (and so how much it delays the audio). */
        const val LOOKAHEAD_SEC = 0.005

        /** A window quieter than this holds the gain. */
        const val SILENCE_GATE_LKFS = -60.0

        private const val PATH_NONE = 0
        private const val PATH_BYPASS = 1
        private const val PATH_LEVELLING = 2
        private const val GATED = -1.0
        private const val HP_B1 = 2.0
        private const val AMPLITUDE_DB = 20.0
        private const val LKFS_OFFSET = -0.691
        private const val DB_PER_DECADE = 10.0
        private const val BLOCKS_PER_SEC = 10
        private const val WINDOW_BLOCKS = 30
        private const val STATE_PER_CHANNEL = 4
        private const val PCM16_SCALE = 32768.0
        private const val SURROUND_MIN = 6
        private const val LFE = 3
        private const val SURROUND_GAIN = 1.41
        private val CEILING_LIN = dbToLin(CEILING_DBFS)
        private val SETTLE_BLOCKS = (SETTLE_SEC * BLOCKS_PER_SEC).roundToInt()
        private val MOMENTARY_BLOCKS = (MOMENTARY_WINDOW_SEC * BLOCKS_PER_SEC).roundToInt()
        private val REMEMBER_EVERY_BLOCKS = (REMEMBER_EVERY_SEC * BLOCKS_PER_SEC).roundToInt()

        private fun dbToLin(db: Double) = 10.0.pow(db / AMPLITUDE_DB)

        private fun lkfs(meanSquare: Double): Double {
            if (meanSquare <= 0.0) return -Double.MAX_VALUE
            return LKFS_OFFSET + DB_PER_DECADE * log10(meanSquare)
        }

        /** BS.1770 weights for Android's order (L R C LFE Ls Rs ...): LFE counts nothing, surrounds 1.41. */
        private fun channelWeights(count: Int): DoubleArray =
            DoubleArray(count) { c ->
                when {
                    count < SURROUND_MIN || c < LFE -> 1.0
                    c == LFE -> 0.0
                    else -> SURROUND_GAIN
                }
            }
    }
}
