// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession

/** A scripted [MediaServer] for tests of code that uses the port (ADR-0004). Not thread-safe. */
class FakeMediaServer : MediaServer {
    var discoverResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var connectResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var signInResult: ServerResult<Session> = ServerResult.Failure(MediaServerError.Unknown())
    var resumeResult: ServerResult<Session> = ServerResult.Failure(MediaServerError.Unknown())
    var revokeResult: ServerResult<Unit> = ServerResult.Success(Unit)
    var quickConnectStates: List<QuickConnectState> = emptyList()

    /** Runs when [revoke] is called, before it answers. */
    var onRevoke: () -> Unit = {}

    /** When set, [revoke] waits for it, to let a test cancel the caller in the middle of the call. */
    var revokeGate: CompletableDeferred<Unit>? = null

    /** Completed when [revoke] has returned. */
    val revokeFinished = CompletableDeferred<Unit>()

    val discoverCalls = mutableListOf<String>()
    val connectCalls = mutableListOf<Pair<ServerAddress, CleartextAcknowledgement?>>()
    val resumeCalls = mutableListOf<StoredSession>()
    val revokeCalls = mutableListOf<Session>()
    val quickConnectCalls = mutableListOf<Pair<ServerAddress, CleartextAcknowledgement?>>()

    /** The addresses and usernames of password sign-ins. Passwords are not kept. */
    val signInCalls = mutableListOf<Pair<ServerAddress, String>>()

    override suspend fun discover(input: String): ServerResult<ResolvedServer> {
        discoverCalls += input
        return discoverResult
    }

    override suspend fun connect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<ResolvedServer> {
        connectCalls += address to acknowledgement
        return connectResult
    }

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        signInCalls += request.address to request.username
        return signInResult
    }

    override suspend fun resume(stored: StoredSession): ServerResult<Session> {
        resumeCalls += stored
        return resumeResult
    }

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> {
        quickConnectCalls += address to acknowledgement
        return quickConnectStates.asFlow()
    }

    override suspend fun revoke(session: Session): ServerResult<Unit> {
        revokeCalls += session
        onRevoke()
        revokeGate?.await()
        revokeFinished.complete(Unit)
        return revokeResult
    }
}
