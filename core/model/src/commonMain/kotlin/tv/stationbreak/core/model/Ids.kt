// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Identifies a media server (Jellyfin's `Id` from the public system info). Never blank. */
@Serializable
@JvmInline
value class ServerId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A server ID can't be blank" }
    }
}

/** Identifies a user on a media server. Never blank. */
@Serializable
@JvmInline
value class UserId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A user ID can't be blank" }
    }
}
