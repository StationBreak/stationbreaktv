// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * A signed-in user on one server. It carries the [address] it was issued for and its [acknowledgement], so every
 * token-bearing call can run the cleartext gate without asking anyone else. [toString] shows neither the token nor
 * the host.
 */
data class Session(
    val userId: UserId,
    val token: AccessToken,
    val server: ServerInfo,
    val address: ServerAddress,
    val acknowledgement: CleartextAcknowledgement?,
    val isAdministrator: Boolean,
) {
    fun toStored(): StoredSession = StoredSession(userId, token, server.id, address, acknowledgement)

    override fun toString(): String {
        val shown = "isAdministrator=$isAdministrator, address=$address, token=$token"
        return "Session($shown, user=<redacted>)"
    }
}

/**
 * What survives a restart: the token and the server it is bound to (server ID and [address]). Not the device ID,
 * which has its own key. The server's name and the user's rights are read again after a restore.
 */
data class StoredSession(
    val userId: UserId,
    val token: AccessToken,
    val serverId: ServerId,
    val address: ServerAddress,
    val acknowledgement: CleartextAcknowledgement?,
) {
    override fun toString(): String = "StoredSession(address=$address, token=$token, user=<redacted>)"
}

/**
 * A password sign-in. The [password] is a `CharArray` so it can be wiped: the adapter zero-fills it when the call
 * ends, whatever the outcome. Hold it no longer than the call.
 */
class SignInRequest(
    val address: ServerAddress,
    val username: String,
    val password: CharArray,
    val acknowledgement: CleartextAcknowledgement?,
) {
    override fun toString(): String = "SignInRequest(address=$address, username=<redacted>, password=***)"
}
