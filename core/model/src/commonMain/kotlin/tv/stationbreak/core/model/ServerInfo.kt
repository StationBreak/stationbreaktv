// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable

/** What a media server says about itself. */
@Serializable
data class ServerInfo(
    val name: String,
    val id: ServerId,
    val version: ServerVersion,
)
