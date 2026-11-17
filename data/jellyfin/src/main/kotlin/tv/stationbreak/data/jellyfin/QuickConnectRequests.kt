// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /**
     * The sign-in that spends the secret. Wipe [Endpoint.body] after the call. The secret is already an unwipeable
     * `String`, and kotlinx.serialization's pooled writer buffer may hold its characters until a later encode reuses
     * it; it is single-use and expires in minutes, so that is accepted (#822).
     */
    @OptIn(SecretAccess::class)
    fun finish(secret: QuickConnectSecret): Endpoint {
        val body = Json.encodeToString(buildJsonObject { put("Secret", secret.reveal) }).toByteArray(Charsets.UTF_8)
        return Endpoint.post("Users/AuthenticateWithQuickConnect", body)
    }
}
