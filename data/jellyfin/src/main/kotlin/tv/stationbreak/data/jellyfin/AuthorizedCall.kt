// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.CleartextGate
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import java.util.concurrent.ConcurrentHashMap

/**
 * The one choke point every token-bearing call goes through (R1, R7). Before a token is sent it checks, in order:
 * the cleartext gate for the session's own address and acknowledgement, and that the server answering at that address
 * is the one that issued the token (a probe, once per session per run; sessions this adapter issued or resumed are
 * already known). A mismatch is `ServerMismatch` and nothing carrying the token has been sent. 3.0's calls reuse this.
 */
internal class AuthorizedCall(
    private val transport: Transport,
    private val probe: ServerProbe,
    private val log: AdapterLog,
) {
    private data class Binding(
        val token: AccessToken,
        val address: tv.stationbreak.core.model.ServerAddress,
        val serverId: ServerId,
    )

    private val bound = ConcurrentHashMap.newKeySet<Binding>()

    /** Records that [session] was just issued by, or confirmed against, the server at its address. */
    fun remember(session: Session) {
        bound += Binding(session.token, session.address, session.server.id)
    }

    /**
     * Whether [session] may carry its token right now, decided without sending anything: its cleartext gate passes and
     * the token is already bound to the server at its address (issued or resumed by this adapter, or confirmed by an
     * earlier call). The same two tests [send] applies, minus the probe a first use would make; a session not yet
     * bound is simply not cleared. For the media client, whose calls can't wait on a probe.
     */
    fun isCleared(session: Session): Boolean =
        CleartextGate.check(session.address, session.acknowledgement) == null &&
            Binding(session.token, session.address, session.server.id) in bound

    /**
     * Drops every binding of [session]'s token, so a revoked or refused token is no longer [isCleared] and the media
     * client stops attaching it, whatever the caller still hands out. Called on sign-out and when a call comes back
     * `SessionExpired` (PR #321/#332, issue 322).
     */
    fun forget(session: Session) {
        bound.removeAll { it.token == session.token }
    }

    suspend fun send(
        session: Session,
        endpoint: Endpoint,
    ): ServerResult<String> {
        val blocked = CleartextGate.check(session.address, session.acknowledgement) ?: ensureBound(session)
        return if (blocked != null) {
            ServerResult.Failure(blocked)
        } else {
            transport.send(session.address, endpoint, session.token).also {
                if (it is ServerResult.Failure && it.error is MediaServerError.SessionExpired) forget(session)
            }
        }
    }

    private suspend fun ensureBound(session: Session): MediaServerError? {
        val binding = Binding(session.token, session.address, session.server.id)
        return when {
            binding in bound -> null

            // A token is bound to the one server that issued it; it is never re-bound to another address.
            bound.any { it.token == session.token } -> MediaServerError.ServerMismatch()

            else -> verify(session, binding)
        }
    }

    private suspend fun verify(
        session: Session,
        binding: Binding,
    ): MediaServerError? =
        when (val answer = probe.probe(session.address)) {
            is ServerResult.Failure -> {
                answer.error
            }

            is ServerResult.Success -> {
                val resolved = answer.value
                when {
                    resolved.server.id != session.server.id -> {
                        log.log("token binding: server mismatch")
                        MediaServerError.ServerMismatch()
                    }

                    // The server moved to another address: the session must be resumed to follow it.
                    resolved.address != session.address -> {
                        MediaServerError.Redirected()
                    }

                    else -> {
                        bound += binding
                        null
                    }
                }
            }
        }
}
