// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The `SBLIVE` diagnostics of the player and the levelling stage (the same tag and gate as live TV's keep-screen-on
 * lines): written on a debuggable build, or on a device switched on with `setprop log.tag.SBLIVE DEBUG` (so a benchmark
 * build can be asked too). A release build on a normal device stays silent. The gate is read once per player build
 * ([configure]); callers on a hot path test [on] before they build a string.
 */
internal object PlaybackLog {
    const val TAG = "SBLIVE"

    /** How many causes [failureText] follows. */
    const val MAX_CAUSES = 8

    private const val MAX_MESSAGE = 200

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
     * The error and its cause chain, [MAX_CAUSES] deep at most: the class name and the message of each, as
     * `Class: message <- Class: message`. A message is cut short and has anything URL-like or token-like blanked,
     * since Media3 messages may quote a playlist line or a request address.
     */
    fun failureText(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .joinToString(" <- ") { "${it.javaClass.name}: ${scrub(it.message)}" }

    private val urlLike = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S*""")
    private val tokenLike = Regex("""(?i)(api_key|apikey|token|x-emby-token|authorization)[=:]\s*\S+""")

    internal fun scrub(message: String?): String {
        val text = message ?: return "null"
        val cleaned = tokenLike.replace(urlLike.replace(text, "<url>"), "$1=<redacted>")
        return cleaned.replace('\n', ' ').take(MAX_MESSAGE)
    }
}
