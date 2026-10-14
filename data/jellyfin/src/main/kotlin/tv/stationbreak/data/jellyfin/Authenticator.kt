// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.CleartextGate
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.flatMap
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId

/**
 * Password sign-in and resuming a stored session. Credentials go only to the address a credential-free probe ended
 * at, after the cleartext gate, on the no-redirect client.
 */
internal class Authenticator(
    private val probe: ServerProbe,
    private val transport: Transport,
    private val calls: AuthorizedCall,
    private val log: AdapterLog,
) {
    /** Gate, then probe. Nothing is sent to an address that needs confirmation and has none. */
    suspend fun reach(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<ResolvedServer> =
        CleartextGate.check(address, acknowledgement)?.let { ServerResult.Failure(it) } ?: probe.probe(address)

    suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        val body = PasswordBody.encode(request.username, request.password)
        return try {
            reach(request.address, request.acknowledgement)
                .flatMap { resolved ->
                    transport
                        .send(resolved.address, Endpoint.post("Users/AuthenticateByName", body))
                        .flatMap { sessionFrom(it, resolved, request.acknowledgement) }
                }.also { logOutcome("signIn", it) }
        } finally {
            body.fill(0)
        }
    }

    suspend fun resume(stored: StoredSession): ServerResult<Session> =
        reach(stored.address, stored.acknowledgement)
            .flatMap { resolved ->
                if (resolved.server.id != stored.serverId || !resolved.address.bindsTo(stored.address)) {
                    ServerResult.Failure(MediaServerError.ServerMismatch())
                } else {
                    confirm(stored, resolved)
                }
            }.also { logOutcome("resume", it) }

    private suspend fun confirm(
        stored: StoredSession,
        resolved: ResolvedServer,
    ): ServerResult<Session> {
        val ack = stored.acknowledgement?.takeIf { it.matches(resolved.address) }
        val session =
            Session(stored.userId, stored.token, resolved.server, resolved.address, ack, isAdministrator = false)
        calls.remember(session)
        return calls.send(session, Endpoint.get("Users/Me")).flatMap { text ->
            Wire.parse(text, UserDto.serializer()).map {
                session.copy(isAdministrator = it.policy?.isAdministrator == true)
            }
        }
    }

    /** Reads an `AuthenticationResult` into a [Session] for the server at [resolved], and remembers it as bound. */
    fun sessionFrom(
        text: String,
        resolved: ResolvedServer,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<Session> =
        Wire.parse(text, AuthResultDto.serializer()).flatMap { dto ->
            if (ServerId(dto.serverId) != resolved.server.id) {
                ServerResult.Failure(MediaServerError.ServerMismatch())
            } else {
                val session =
                    Session(
                        UserId(dto.user.id),
                        AccessToken(dto.accessToken),
                        resolved.server,
                        resolved.address,
                        acknowledgement?.takeIf { it.matches(resolved.address) },
                        dto.user.policy?.isAdministrator == true,
                    )
                calls.remember(session)
                ServerResult.Success(session)
            }
        }

    private fun logOutcome(
        what: String,
        result: ServerResult<*>,
    ) = log.log(
        when (result) {
            is ServerResult.Success -> "$what ok"
            is ServerResult.Failure -> "$what failed: ${result.error::class.simpleName}"
        },
    )
}
