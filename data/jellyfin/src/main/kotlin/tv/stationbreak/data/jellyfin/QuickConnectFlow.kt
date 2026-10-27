// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.flatMap
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.QuickConnectSecret
import tv.stationbreak.core.model.ServerAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long Quick Connect waits for approval: one poll every [interval] until [total] has passed (S0.3.2). */
internal data class QuickConnectLimits(
    val interval: Duration = 5.seconds,
    val total: Duration = 5.minutes,
) {
    val polls: Int get() = (total / interval).toInt()
}

/**
 * The Quick Connect sign-in: checks the server has it on, asks for a code, polls until approval, expiry or the client
 * limit, then finishes with `AuthenticateWithQuickConnect`. The secret lives in this class's locals only: it is in no
 * emitted state, error or log line.
 */
internal class QuickConnectFlow(
    private val authenticator: Authenticator,
    private val transport: Transport,
    private val limits: QuickConnectLimits,
    private val log: AdapterLog,
) {
    private class Issued(
        val secret: QuickConnectSecret,
        val code: String,
    )

    private sealed interface Step {
        data object Pending : Step

        class Done(
            val state: QuickConnectState,
        ) : Step
    }

    fun run(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> =
        flow {
            val reached = authenticator.reach(address, acknowledgement)
            val terminal =
                when (reached) {
                    is ServerResult.Failure -> QuickConnectState.Failed(reached.error)
                    is ServerResult.Success -> issueAndPoll(reached.value, acknowledgement) { emit(it) }
                }
            log.log("quickConnect ended: ${terminal.logName()}")
            emit(terminal)
        }

    private suspend fun issueAndPoll(
        resolved: ResolvedServer,
        acknowledgement: CleartextAcknowledgement?,
        emit: suspend (QuickConnectState) -> Unit,
    ): QuickConnectState =
        when (val issued = issue(resolved)) {
            is ServerResult.Failure -> {
                QuickConnectState.Failed(issued.error)
            }

            is ServerResult.Success -> {
                emit(QuickConnectState.CodeIssued(issued.value.code))
                poll(resolved, acknowledgement, issued.value.secret, emit)
            }
        }

    private suspend fun issue(resolved: ResolvedServer): ServerResult<Issued> =
        transport
            .send(resolved.address, Endpoint.get("QuickConnect/Enabled"))
            .flatMap { text ->
                if (text.trim() == "true") {
                    initiate(resolved)
                } else {
                    ServerResult.Failure(MediaServerError.QuickConnectDisabled())
                }
            }

    // A server with Quick Connect off may refuse the request instead of answering `false` to the check.
    private suspend fun initiate(resolved: ResolvedServer): ServerResult<Issued> {
        val answer = transport.send(resolved.address, Endpoint.post("QuickConnect/Initiate"))
        val refused = answer is ServerResult.Failure && answer.error.isRefusal()
        return if (refused) {
            ServerResult.Failure(MediaServerError.QuickConnectDisabled())
        } else {
            answer
                .flatMap { Wire.parse(it, InitiateDto.serializer()) }
                .map { Issued(QuickConnectSecret(it.secret), it.code) }
        }
    }

    private fun MediaServerError.isRefusal(): Boolean {
        val unauthorized = this is MediaServerError.HttpUnauthorized
        return unauthorized || this is MediaServerError.HttpForbidden
    }

    private suspend fun poll(
        resolved: ResolvedServer,
        acknowledgement: CleartextAcknowledgement?,
        secret: QuickConnectSecret,
        emit: suspend (QuickConnectState) -> Unit,
    ): QuickConnectState {
        var waiting = false
        var outcome: QuickConnectState? = null
        var left = limits.polls
        // The poll count is not enough: slow polls would stretch 5 minutes, so a deadline ends it too.
        val deadline = TimeSource.Monotonic.markNow() + limits.total
        while (outcome == null && left-- > 0 && deadline.hasNotPassedNow()) {
            delay(limits.interval)
            when (val step = pollOnce(resolved, acknowledgement, secret)) {
                is Step.Done -> {
                    outcome = step.state
                }

                Step.Pending -> {
                    if (!waiting) {
                        waiting = true
                        emit(QuickConnectState.Waiting)
                    }
                }
            }
        }
        return outcome ?: QuickConnectState.Expired
    }

    private suspend fun pollOnce(
        resolved: ResolvedServer,
        acknowledgement: CleartextAcknowledgement?,
        secret: QuickConnectSecret,
    ): Step =
        when (val answer = transport.send(resolved.address, QuickConnectRequests.poll(secret))) {
            is ServerResult.Failure -> {
                Step.Done(pollFailure(answer.error))
            }

            is ServerResult.Success -> {
                when (val state = Wire.parse(answer.value, PollDto.serializer())) {
                    is ServerResult.Failure -> {
                        Step.Done(QuickConnectState.Failed(state.error))
                    }

                    is ServerResult.Success -> {
                        if (state.value.authenticated) {
                            Step.Done(
                                finish(resolved, acknowledgement, secret),
                            )
                        } else {
                            Step.Pending
                        }
                    }
                }
            }
        }

    // The server forgetting the code is expiry; a refusal is the user (or the server) saying no.
    private fun pollFailure(error: MediaServerError): QuickConnectState =
        when {
            error is MediaServerError.NotFound -> QuickConnectState.Expired
            error.isRefusal() -> QuickConnectState.Denied
            else -> QuickConnectState.Failed(error)
        }

    private suspend fun finish(
        resolved: ResolvedServer,
        acknowledgement: CleartextAcknowledgement?,
        secret: QuickConnectSecret,
    ): QuickConnectState {
        val endpoint = QuickConnectRequests.finish(secret)
        val result =
            try {
                transport
                    .send(resolved.address, endpoint)
                    .flatMap { authenticator.sessionFrom(it, resolved, acknowledgement) }
            } finally {
                endpoint.body?.fill(0)
            }
        return when (result) {
            is ServerResult.Success -> QuickConnectState.SignedIn(result.value)
            is ServerResult.Failure -> QuickConnectState.Failed(result.error)
        }
    }
}
