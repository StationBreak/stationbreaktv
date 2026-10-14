// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.model.QuickConnectSecret
import tv.stationbreak.core.model.SecretAccess

/**
 * The only place the Quick Connect secret is read raw (it is a credential). The Jellyfin API takes it as the
 * `secret` query parameter of `GET QuickConnect/Connect` and as the body of the final sign-in. The URL is therefore
 * the one place a credential-like value travels in a query; it is single-use, expires in minutes, and is never logged.
 */
internal object QuickConnectRequests {
    @OptIn(SecretAccess::class)
    fun poll(secret: QuickConnectSecret): Endpoint {
        val query = listOf("secret" to secret.reveal)
        return Endpoint.get("QuickConnect/Connect", query)
    }

    /** The sign-in that spends the secret. Wipe [Endpoint.body] after the call. */
    @OptIn(SecretAccess::class)
    fun finish(secret: QuickConnectSecret): Endpoint {
        val body = "{\"Secret\":${JsonText.quote(secret.reveal)}}".toByteArray(Charsets.UTF_8)
        return Endpoint.post("Users/AuthenticateWithQuickConnect", body)
    }
}

internal object JsonText {
    private const val FIRST_PRINTABLE = ' '
    private const val HEX = 16
    private const val UNICODE_DIGITS = 4

    /** A JSON string literal for [value], escaping quotes, backslashes and control characters. */
    fun quote(value: CharSequence): String =
        buildString {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < FIRST_PRINTABLE -> append("\\u").append(c.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }
}
