// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.model.Redactor

/**
 * Where the adapter's own log statements go. A seam, no-op by default. Every statement is a fixed phrase and an
 * error's type name: never a header, a body, a URL, a host, a username or a secret (R18). [redacting] is the backstop
 * the app wires in front of any real sink (F0.4): if a statement ever does carry a credential, the sink doesn't see it.
 */
fun interface AdapterLog {
    fun log(message: String)

    /**
     * A statement about a failure. The default joins the message, the error's type name and its message into one line.
     * OkHttp exception messages carry the full URL, so only [redacting] makes this safe to hand to a real sink.
     */
    fun log(
        message: String,
        error: Throwable,
    ) = log(describe(message, error))

    companion object {
        val NONE = AdapterLog { }

        /**
         * Wraps [sink] so every line, and every error's type and message, is run through [Redactor] first. The sink
         * gets one redacted line; the throwable itself, with its message, cause and stack, is never passed on.
         */
        fun redacting(sink: AdapterLog): AdapterLog = RedactingAdapterLog(sink)

        internal fun describe(
            message: String,
            error: Throwable,
        ): String {
            val type = error::class.simpleName ?: "Throwable"
            val detail = error.message
            return if (detail.isNullOrEmpty()) "$message: $type" else "$message: $type: $detail"
        }
    }
}

/** The decorator [AdapterLog.redacting] returns. Public only so a test can assert what the graph provides. */
class RedactingAdapterLog internal constructor(
    private val sink: AdapterLog,
) : AdapterLog {
    override fun log(message: String) = sink.log(Redactor.redact(message))

    override fun log(
        message: String,
        error: Throwable,
    ) = sink.log(Redactor.redact(AdapterLog.describe(message, error)))

    override fun toString(): String = "RedactingAdapterLog"
}
