// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

/** The transport a server address uses. Only these two exist: anything else is an invalid address. */
@Serializable
enum class Scheme(
    val text: String,
    val defaultPort: Int,
) {
    Http("http", HTTP_PORT),
    Https("https", HTTPS_PORT),
}
