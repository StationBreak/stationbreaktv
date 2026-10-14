// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/** What a [MediaServer] call returns: a value, or a typed [MediaServerError]. Calls don't throw for server problems. */
sealed interface ServerResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ServerResult<T>

    data class Failure(
        val error: MediaServerError,
    ) : ServerResult<Nothing>
}

fun <T> ServerResult<T>.getOrNull(): T? = (this as? ServerResult.Success)?.value

fun ServerResult<*>.errorOrNull(): MediaServerError? = (this as? ServerResult.Failure)?.error

inline fun <T, R> ServerResult<T>.map(transform: (T) -> R): ServerResult<R> =
    when (this) {
        is ServerResult.Success -> ServerResult.Success(transform(value))
        is ServerResult.Failure -> this
    }

inline fun <T, R> ServerResult<T>.flatMap(transform: (T) -> ServerResult<R>): ServerResult<R> =
    when (this) {
        is ServerResult.Success -> transform(value)
        is ServerResult.Failure -> this
    }
