// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.logName

/**
 * Runs the reporter's server calls: each under [ReportingConfig.callTimeout], none throwing but for cancellation, every
 * failure logged as a label. A 401 sets [halted]: no further call is worth making this run (R9).
 */
internal class ReportCalls(
    private val config: ReportingConfig,
    private val log: (String) -> Unit,
) {
    var halted = false
        private set

    /** The value of [block], or null after logging why not (a timeout, an error result, an exception). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Reporting is best-effort; cancellation still passes.
    suspend fun <T> call(block: suspend () -> ServerResult<T>): T? {
        val result =
            try {
                withTimeoutOrNull(config.callTimeout) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("report error kind=exception")
                return null
            }
        return when (result) {
            is ServerResult.Success -> result.value
            is ServerResult.Failure -> failed(result.error)
            null -> null.also { log("report error kind=timeout") }
        }
    }

    private fun failed(error: MediaServerError): Nothing? {
        if (error is MediaServerError.HttpUnauthorized || error is MediaServerError.SessionExpired) halted = true
        log("report error kind=${error.logName().lowercase()}")
        return null
    }
}
