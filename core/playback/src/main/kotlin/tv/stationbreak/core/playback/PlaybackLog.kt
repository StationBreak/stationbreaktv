// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodec
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import tv.stationbreak.core.model.Redactor

/**
 * The `SBLIVE` diagnostics of the player and the levelling stage (the same tag and gate as live TV's keep-screen-on
 * lines): written on a debuggable build, or on a device switched on with `setprop log.tag.SBLIVE DEBUG` (so a benchmark
 * build can be asked too). A release build on a normal device stays silent. The gate is read once per player build
 * ([configure]); callers on a hot path test [on] before they build a string.
 *
 * What a player error line may carry (ADR-0016, 2026-11-11): class names, error codes, format fields, and a message
 * only from a decoder, audio-sink or audio-processor cause, redacted. Never a URI, a host or a title.
 */
@UnstableApi
internal object PlaybackLog {
    const val TAG = "SBLIVE"

    /** How many throwables [failureText] describes: the error and its causes. */
    const val MAX_CAUSES = 8

    private const val MAX_MESSAGE = 200

    /** Where an IllegalState, IllegalArgument or IndexOutOfBounds is from the codec or audio-processor path. */
    private val CODEC_PATHS =
        listOf(
            "tv.stationbreak.core.playback.LevellingAudioProcessor",
            "androidx.media3.exoplayer.audio.",
            "androidx.media3.exoplayer.mediacodec.",
            "androidx.media3.common.audio.",
            "android.media.",
        )

    @Volatile
    var on: Boolean = false
        private set

    fun configure(context: Context) {
        on = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
    }

    /** One line, only when [on]; [text] is built by the caller, so build it behind a test of [on]. */
    fun line(text: String) {
        if (on) runCatching { Log.i(TAG, text) }
    }

    /**
     * One line for a player error, only when [on]: [head] (fixed fields) then [failureText]. The whole build is inside
     * runCatching, so logging can never throw out of an error callback.
     */
    fun playerError(
        error: PlaybackException,
        head: () -> String,
    ) {
        if (!on) return
        runCatching { Log.i(TAG, "${head()} ${failureText(error)}") }
    }

    /**
     * The error as `code=NAME` (never the top-level message: it can quote a track label), the renderer's format
     * fields when it names one, then each cause as its class name. Only decoder, audio-sink and audio-processor
     * causes keep a message (redacted, cut), and for them the top stack frame of the deepest cause is added.
     * IOException, ParserException and HTTP causes are class names only (plus the status of an invalid response).
     */
    fun failureText(error: Throwable): String {
        val top = if (error is PlaybackException) "code=${error.errorCodeName}" else describe(error)
        val renderer = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
        val format = renderer?.rendererFormat?.let { " format=${formatFields(it)}" }.orEmpty()
        val causes = generateSequence(error.cause) { it.cause }.take(MAX_CAUSES - 1).toList()
        val chain = causes.joinToString("") { " <- ${describe(it)}" }
        val frame =
            if ((listOf(error) + causes).any(::keepsMessage)) {
                " at=${topFrame(causes.lastOrNull() ?: error)}"
            } else {
                ""
            }
        return top + format + chain + frame
    }

    private fun formatFields(format: Format): String =
        "mime=${format.sampleMimeType} channels=${format.channelCount} rate=${format.sampleRate} " +
            "pcm=${format.pcmEncoding} codecs=${format.codecs}"

    private fun describe(error: Throwable): String {
        val status = (error as? HttpDataSource.InvalidResponseCodeException)?.let { " status=${it.responseCode}" }
        val message = if (keepsMessage(error)) error.message?.let { ": ${scrub(it)}" } else null
        return error.javaClass.name + status.orEmpty() + message.orEmpty()
    }

    private fun topFrame(error: Throwable): String =
        error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "none"

    /** A decoder, audio-sink or audio-processor failure: its message is ours or the codec's, not the network's. */
    internal fun keepsMessage(error: Throwable): Boolean =
        when (error) {
            is AudioSink.InitializationException,
            is AudioSink.WriteException,
            is MediaCodecRenderer.DecoderInitializationException,
            is MediaCodec.CodecException,
            -> {
                true
            }

            is IllegalStateException,
            is IllegalArgumentException,
            is IndexOutOfBoundsException,
            is NegativeArraySizeException,
            -> {
                error.stackTrace
                    .firstOrNull()
                    ?.className
                    ?.let { name -> CODEC_PATHS.any(name::startsWith) } == true
            }

            else -> {
                false
            }
        }

    internal fun scrub(message: String): String = Redactor.redact(message).replace('\n', ' ').take(MAX_MESSAGE)
}
